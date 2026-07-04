import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:mime/mime.dart';
import '../services/image_text_extractor.dart';
import 'package:syncfusion_flutter_pdf/pdf.dart';
import '../core/widgets/app_drawer.dart';
import '../providers/chat_provider.dart';
import '../models/message_model.dart';
import 'contact_us_screen.dart';
import 'home/home_screen.dart';
import 'settings_screen.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Glassmorphic design tokens
// ─────────────────────────────────────────────────────────────────────────────
const _bg          = Colors.black;
const _accent      = Color(0xFF23A6E2);
const _accentGlow  = Color(0x3323A6E2);
const _accentDim   = Color(0xFF0A1A28);
const _textPri     = Colors.white;
const _textMuted   = Color(0xFF6B7280);
const _textDim     = Color(0xFF4A5568);
const _userBubble  = Color(0x1A00F0FF);
const _botBubble   = Color(0x0DFFFFFF);
const _danger      = Color(0xFFEF4444);
const _success     = Color(0xFF34C47C);
const _logoPath    = 'lib/img/logo.jpg';

// ─────────────────────────────────────────────────────────────────────────────

Future<String> _extractTextFromPdfBytes(List<int> bytes) async {
  try {
    final doc       = PdfDocument(inputBytes: Uint8List.fromList(bytes));
    final extractor = PdfTextExtractor(doc);
    final buf       = StringBuffer();
    for (int i = 0; i < doc.pages.count; i++) {
      final t = extractor.extractText(startPageIndex: i, endPageIndex: i);
      if (t.trim().isNotEmpty) { buf.writeln(t.trim()); buf.writeln(); }
    }
    doc.dispose();
    return buf.toString().trim();
  } catch (e) {
    debugPrint('[PDF] $e');
    return '';
  }
}

Future<String> _readTextFile(File file) async {
  try { return await file.readAsString(); }
  catch (_) { return utf8.decode(await file.readAsBytes(), allowMalformed: true); }
}

// ─────────────────────────────────────────────────────────────────────────────

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});
  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen>
    with SingleTickerProviderStateMixin {
  final _controller  = TextEditingController();
  final _scrollCtrl  = ScrollController();
  final _focusNode   = FocusNode();
  late AnimationController _fadeCtrl;
  late Animation<double>   _fadeAnim;

  File?   _selectedFile;
  String? _base64File, _mimeType, _fileName, _imageExtractedText;
  bool    _processingDoc = false;

  @override
  void initState() {
    super.initState();
    _fadeCtrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 360));
    _fadeAnim = CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut);
    _fadeCtrl.forward();
  }

  @override
  void dispose() {
    _fadeCtrl.dispose(); _controller.dispose();
    _scrollCtrl.dispose(); _focusNode.dispose();
    super.dispose();
  }

  // ── Attach ─────────────────────────────────────────────────────────────────

  void _pickAttachment() {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xCC111111),
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (_) => _AttachSheet(onPicked: _handlePick),
    );
  }

  Future<void> _handlePick(String type) async {
    switch (type) {
      case 'pdf':  await _pickDoc(['pdf']); break;
      case 'text': await _pickDoc(['txt', 'md', 'csv', 'json', 'log']); break;
      case 'image':
        final img = await ImagePicker().pickImage(source: ImageSource.gallery);
        if (img != null) await _processAttachment(File(img.path));
        break;
      case 'file':
        final r = await FilePicker.platform.pickFiles();
        if (r?.files.single.path != null)
          await _processAttachment(File(r!.files.single.path!));
        break;
    }
  }

  Future<void> _pickDoc(List<String> exts) async {
    final r = await FilePicker.platform
        .pickFiles(type: FileType.custom, allowedExtensions: exts);
    if (r == null || r.files.single.path == null) return;
    final file = File(r.files.single.path!);
    final name = r.files.single.name;
    setState(() => _processingDoc = true);
    try {
      final text = name.split('.').last == 'pdf'
          ? await _extractTextFromPdfBytes(await file.readAsBytes())
          : await _readTextFile(file);
      if (!mounted) return;
      if (text.trim().isEmpty) {
        _snack('Could not extract text — file may be image-based.', err: true);
        return;
      }
      ref.read(chatNotifierProvider.notifier)
          .loadDocument(DocumentContext(fileName: name, text: text));
      _snack('📄 "$name" — ${(text.length / 1000).toStringAsFixed(1)}k chars loaded');
    } catch (e) {
      if (mounted) _snack('Error: $e', err: true);
    } finally {
      if (mounted) setState(() => _processingDoc = false);
    }
  }

  Future<void> _processAttachment(File file) async {
    try {
      final bytes = await file.readAsBytes();
      final mimeType = lookupMimeType(file.path) ?? 'application/octet-stream';
      String? extractedText;

      if (mimeType.startsWith('image/')) {
        setState(() => _processingDoc = true);
        extractedText = await _extractTextFromImage(file);
      }

      setState(() {
        _selectedFile = file;
        _base64File = base64Encode(bytes);
        _mimeType = mimeType;
        _fileName = file.path.split('/').last;
        _imageExtractedText = extractedText;
      });

      if (mimeType.startsWith('image/')) {
        _snack(extractedText?.trim().isNotEmpty == true
            ? '🖼️ Image text extracted and attached.'
            : '🖼️ Image attached. No readable text found locally.');
      }
    } catch (e) {
      _snack('Error: $e', err: true);
    } finally {
      if (mounted) setState(() => _processingDoc = false);
    }
  }

  Future<String> _extractTextFromImage(File file) async {
    try {
      return await ImageTextExtractor.extractText(file.path);
    } catch (e) {
      debugPrint('[OCR] $e');
      return '';
    }
  }

  void _clearAttach() => setState(() {
        _selectedFile = null;
        _base64File = null;
        _mimeType = null;
        _fileName = null;
        _imageExtractedText = null;
      });

  // ── Send ───────────────────────────────────────────────────────────────────

  void _send() {
    final text = _controller.text.trim();
    if (text.isEmpty && _selectedFile == null) return;

    final hasImage = _mimeType?.startsWith('image/') == true;
    final ocrText = _imageExtractedText?.trim();
    final outboundText = hasImage && ocrText?.isNotEmpty == true
        ? [
            if (text.isNotEmpty) text else 'Please analyze this image.',
            '\nExtracted image text for reasoning:\n$ocrText',
          ].join('\n')
        : (text.isNotEmpty ? text : 'Please analyze this attachment.');

    final visibleText = text.isNotEmpty
        ? text
        : (hasImage ? 'Sent image: $_fileName' : 'Sent attachment: $_fileName');

    ref.read(chatNotifierProvider.notifier).sendMessage(outboundText,
        attachment: _base64File,
        mimeType: _mimeType,
        fileName: _fileName,
        displayText: visibleText);
    _controller.clear(); _clearAttach(); _focusNode.unfocus(); _scrollDown();
  }

  void _scrollDown() {
    Future.delayed(const Duration(milliseconds: 300), () {
      if (_scrollCtrl.hasClients)
        _scrollCtrl.animateTo(_scrollCtrl.position.maxScrollExtent,
            duration: const Duration(milliseconds: 300), curve: Curves.easeOut);
    });
  }

  void _snack(String msg, {bool err = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(color: _textPri, fontSize: 13)),
      backgroundColor: err ? const Color(0xFF1A0808) : _accentDim,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ));
  }

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(chatNotifierProvider);
    final docCtx    = ref.watch(documentContextProvider);
    ref.listen<AsyncValue<List<Message>>>(
        chatNotifierProvider, (_, next) => next.whenData((_) => _scrollDown()));

    return Scaffold(
      backgroundColor: _bg,
      appBar: _appBar(docCtx),
      drawer: _drawer(),
      body: FadeTransition(
        opacity: _fadeAnim,
        child: Column(
          children: [
            if (docCtx != null) _docBanner(docCtx),
            Expanded(child: _msgList(chatState)),
            if (_processingDoc) _processingBar(),
            if (_selectedFile != null) _filePreview(),
            _inputArea(docCtx),
          ],
        ),
      ),
    );
  }

  // ── AppBar — pure black, home-screen style ─────────────────────────────────
  PreferredSizeWidget _appBar(DocumentContext? docCtx) {
    return AppBar(
      backgroundColor: const Color(0x4D000000),
      elevation: 0,
      surfaceTintColor: Colors.transparent,
      leading: Builder(
        builder: (ctx) => GestureDetector(
          onTap: () => Scaffold.of(ctx).openDrawer(),
          child: const Padding(
            padding: EdgeInsets.all(12),
            child: Icon(Icons.menu, color: _textPri, size: 26),
          ),
        ),
      ),
      title: Row(
        children: [
          // Brand logo
          ClipRRect(
            borderRadius: BorderRadius.circular(7),
            child: Image.asset(_logoPath, width: 26, height: 26,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const Icon(
                    Icons.auto_awesome, color: _accent, size: 20)),
          ),
          const SizedBox(width: 10),
          const Text('STREMINI AI',
              style: TextStyle(
                  color: _textPri,
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 2.0)),
          if (docCtx != null) ...[
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
              decoration: BoxDecoration(
                color: _accent.withOpacity(0.1),
                borderRadius: BorderRadius.circular(5),
                border: Border.all(color: _accent.withOpacity(0.3)),
              ),
              child: const Text('DOC',
                  style: TextStyle(
                      color: _accent,
                      fontSize: 9,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 1.0)),
            ),
          ],
        ],
      ),
      actions: [
        GestureDetector(
          onTap: () => ref.read(chatNotifierProvider.notifier).clearChat(),
          child: Container(
            margin: const EdgeInsets.fromLTRB(0, 10, 14, 10),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: const Color(0x0DFFFFFF),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
            ),
            child: const Text('Clear',
                style: TextStyle(
                    color: _textMuted,
                    fontSize: 12,
                    fontWeight: FontWeight.w600)),
          ),
        ),
      ],
      bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: const Color(0x1AFFFFFF))),
    );
  }

  // ── Doc Banner ─────────────────────────────────────────────────────────────
  Widget _docBanner(DocumentContext doc) {
    return Container(
      color: const Color(0x08000000),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
      child: Row(
        children: [
          Container(
            width: 32, height: 32,
            decoration: BoxDecoration(
              color: _danger.withOpacity(0.08),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: _danger.withOpacity(0.2)),
            ),
            child: const Icon(Icons.picture_as_pdf, color: _danger, size: 15),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(doc.fileName,
                  style: const TextStyle(
                      color: _textPri, fontSize: 12, fontWeight: FontWeight.w700),
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              const Text('Ask anything about this document',
                  style: TextStyle(color: _textMuted, fontSize: 11)),
            ]),
          ),
          GestureDetector(
            onTap: () => ref.read(chatNotifierProvider.notifier).clearDocument(),
            child: Container(
              width: 26, height: 26,
              decoration: BoxDecoration(
                color: const Color(0x0DFFFFFF), borderRadius: BorderRadius.circular(7),
                border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
              ),
              child: const Icon(Icons.close, color: _textMuted, size: 13),
            ),
          ),
        ],
      ),
    );
  }

  // ── Message list ───────────────────────────────────────────────────────────
  Widget _msgList(AsyncValue<List<Message>> chatState) {
    return chatState.when(
      data: (msgs) => msgs.isEmpty ? _emptyState() : ListView.builder(
        controller: _scrollCtrl,
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 10),
        itemCount: msgs.length,
        itemBuilder: (_, i) {
          final prev = i > 0 ? msgs[i - 1] : null;
          return _bubble(msgs[i], showAvatar: prev?.type != msgs[i].type);
        },
      ),
      loading: () => const Center(child: CircularProgressIndicator(
          strokeWidth: 2, valueColor: AlwaysStoppedAnimation(_accent))),
      error: (e, _) => Center(child: Text('Error: $e',
          style: const TextStyle(color: _danger, fontSize: 13))),
    );
  }

  Widget _emptyState() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(
          width: 56, height: 56,
          decoration: BoxDecoration(
            color: const Color(0x0DFFFFFF),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(13),
            child: Image.asset(_logoPath, fit: BoxFit.cover,
                errorBuilder: (_, __, ___) =>
                    const Icon(Icons.auto_awesome, color: _accent, size: 26)),
          ),
        ),
        const SizedBox(height: 16),
        const Text('STREMINI AI',
            style: TextStyle(
                color: _textPri, fontSize: 15,
                fontWeight: FontWeight.w800, letterSpacing: 2.0)),
        const SizedBox(height: 6),
        const Text('How can I help you today?',
            style: TextStyle(color: _textMuted, fontSize: 13)),
      ]),
    );
  }

  // ── Bubble ─────────────────────────────────────────────────────────────────
  Widget _bubble(Message message, {bool showAvatar = true}) {
    switch (message.type) {
      case MessageType.typing:
        return _typingBubble();
      case MessageType.documentBanner:
        return _docAnnounce(message.text);
      default:
        final isUser = message.type == MessageType.user;
        return Padding(
          padding: EdgeInsets.only(bottom: 4, top: showAvatar ? 14 : 2),
          child: Row(
            mainAxisAlignment: isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (!isUser && showAvatar) ...[_botAvatar(), const SizedBox(width: 8)],
              if (!isUser && !showAvatar) const SizedBox(width: 34),
              Flexible(
                child: ClipRRect(
                  borderRadius: BorderRadius.only(
                    topLeft:     const Radius.circular(14),
                    topRight:    const Radius.circular(14),
                    bottomLeft:  Radius.circular(isUser ? 14 : 4),
                    bottomRight: Radius.circular(isUser ? 4 : 14),
                  ),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                      decoration: BoxDecoration(
                        color: isUser ? _userBubble : _botBubble,
                        borderRadius: BorderRadius.only(
                          topLeft:     const Radius.circular(14),
                          topRight:    const Radius.circular(14),
                          bottomLeft:  Radius.circular(isUser ? 14 : 4),
                          bottomRight: Radius.circular(isUser ? 4 : 14),
                        ),
                        border: Border.all(
                          color: isUser ? const Color(0x3300F0FF) : const Color(0x14FFFFFF),
                          width: 0.5,
                        ),
                      ),
                      child: SelectableText(message.text,
                          style: const TextStyle(
                              color: _textPri, fontSize: 14, height: 1.55),
                          cursorColor: _accent),
                    ),
                  ),
                ),
              ),
              if (isUser && showAvatar) ...[const SizedBox(width: 8), _userAvatar()],
              if (isUser && !showAvatar) const SizedBox(width: 30),
            ],
          ),
        );
    }
  }

  // Avatar — logo image for bot, person icon for user ────────────────────────
  Widget _botAvatar() => ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Container(
          width: 28, height: 28,
          decoration: BoxDecoration(
            color: const Color(0x0DFFFFFF),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
          ),
          child: Image.asset(_logoPath, width: 28, height: 28,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) =>
                  const Icon(Icons.auto_awesome, color: _accent, size: 13)),
        ),
      );

  Widget _userAvatar() => Container(
        width: 28, height: 28,
        decoration: BoxDecoration(
          color: const Color(0x0DFFFFFF),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
        ),
        child: const Icon(Icons.person_outline, color: _textMuted, size: 14),
      );

  Widget _docAnnounce(String text) => Container(
        margin: const EdgeInsets.symmetric(vertical: 8),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: const Color(0x0DFFFFFF),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0x3D23A6E2), width: 0.5),
        ),
        child: Row(children: [
          const Icon(Icons.picture_as_pdf, color: _danger, size: 15),
          const SizedBox(width: 10),
          Expanded(child: Text(text,
              style: const TextStyle(color: _textMuted, fontSize: 13, height: 1.5))),
        ]),
      );

  Widget _typingBubble() => Padding(
        padding: const EdgeInsets.only(bottom: 4, top: 14),
        child: Row(children: [
          _botAvatar(),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
            decoration: BoxDecoration(
              color: _botBubble,
              borderRadius: const BorderRadius.only(
                topLeft:     Radius.circular(14),
                topRight:    Radius.circular(14),
                bottomRight: Radius.circular(14),
                bottomLeft:  Radius.circular(4),
              ),
              border: Border.all(color: const Color(0x14FFFFFF), width: 0.5),
            ),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation(_accent),
                  ),
                ),
                SizedBox(width: 10),
                Text(
                  'Thinking…',
                  style: TextStyle(
                    color: _textPri,
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ]),
      );

  // ── Processing / file preview ──────────────────────────────────────────────
  Widget _processingBar() => Container(
        color: const Color(0x0DFFFFFF),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        child: Row(children: const [
          SizedBox(width: 14, height: 14,
            child: CircularProgressIndicator(strokeWidth: 2,
                valueColor: AlwaysStoppedAnimation(_accent))),
          SizedBox(width: 10),
          Text('Extracting content…',
              style: TextStyle(color: _textMuted, fontSize: 13)),
        ]),
      );

  Widget _filePreview() => Container(
        margin: const EdgeInsets.fromLTRB(14, 4, 14, 0),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: const Color(0x0DFFFFFF),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
        ),
        child: Row(children: [
          Container(
            width: 36, height: 36,
            decoration: BoxDecoration(color: _accentDim, borderRadius: BorderRadius.circular(8)),
            child: _mimeType?.startsWith('image/') == true
                ? ClipRRect(borderRadius: BorderRadius.circular(8),
                    child: Image.file(_selectedFile!, fit: BoxFit.cover))
                : const Icon(Icons.insert_drive_file, color: _accent, size: 17),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(_fileName ?? 'Attached File',
                    style: const TextStyle(color: _textPri, fontSize: 13),
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                if (_mimeType?.startsWith('image/') == true &&
                    _imageExtractedText?.trim().isNotEmpty == true)
                  const Text('Text extracted for AI reasoning',
                      style: TextStyle(color: _success, fontSize: 10)),
              ],
            ),
          ),
          GestureDetector(
            onTap: _clearAttach,
            child: Container(
              padding: const EdgeInsets.all(5),
              decoration: BoxDecoration(
                color: const Color(0xFF1A0808),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: _danger.withOpacity(0.25)),
              ),
              child: const Icon(Icons.close, color: _danger, size: 13),
            ),
          ),
        ]),
      );

  // ── Input Area ─────────────────────────────────────────────────────────────
  Widget _inputArea(DocumentContext? docCtx) {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
      decoration: const BoxDecoration(
        color: Colors.black,
        border: Border(top: BorderSide(color: Color(0x1AFFFFFF))),
      ),
      child: SafeArea(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            // Attach button
            GestureDetector(
              onTap: _pickAttachment,
              child: Container(
                width: 40, height: 40,
                margin: const EdgeInsets.only(bottom: 6),
                decoration: BoxDecoration(
                  color: const Color(0x0DFFFFFF),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
                ),
                child: const Icon(Icons.add_rounded, color: _textMuted, size: 20),
              ),
            ),
            const SizedBox(width: 8),
            // Text field
            Expanded(
              child: Container(
                constraints: const BoxConstraints(maxHeight: 120),
                margin: const EdgeInsets.only(bottom: 6),
                decoration: BoxDecoration(
                  color: const Color(0x0DFFFFFF),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(
                    color: docCtx != null ? const Color(0x3300F0FF) : const Color(0x1AFFFFFF),
                    width: 0.5,
                  ),
                ),
                child: TextField(
                  controller: _controller,
                  focusNode: _focusNode,
                  style: const TextStyle(color: _textPri, fontSize: 14, height: 1.45),
                  decoration: InputDecoration(
                    hintText: docCtx != null
                        ? 'Ask about ${docCtx.fileName}…'
                        : 'Message Stremini…',
                    hintStyle: const TextStyle(color: _textDim, fontSize: 14),
                    border: InputBorder.none,
                    contentPadding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 10),
                  ),
                  maxLines: null,
                  textInputAction: TextInputAction.send,
                  onSubmitted: (_) => _send(),
                ),
              ),
            ),
            const SizedBox(width: 8),
            // Send button
            GestureDetector(
              onTap: _send,
              child: Container(
                width: 40, height: 40,
                margin: const EdgeInsets.only(bottom: 6),
                decoration: BoxDecoration(
                  color: _accent,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(Icons.arrow_upward_rounded,
                    color: Colors.white, size: 20),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ── Drawer ─────────────────────────────────────────────────────────────────
  Widget _drawer() => AppDrawer(items: [
        AppDrawerItem(icon: Icons.home_outlined, title: 'Home', onTap: () {
          Navigator.pop(context);
          Navigator.pushReplacement(
              context, MaterialPageRoute(builder: (_) => const HomeScreen()));
        }),
        AppDrawerItem(icon: Icons.settings_outlined, title: 'Settings', onTap: () {
          Navigator.pop(context);
          Navigator.push(context,
              MaterialPageRoute(builder: (_) => const SettingsScreen()));
        }),
        AppDrawerItem(icon: Icons.help_outline, title: 'Contact Us', onTap: () {
          Navigator.pop(context);
          Navigator.push(context,
              MaterialPageRoute(builder: (_) => const ContactUsScreen()));
        }),
      ]);
}

// ─────────────────────────────────────────────────────────────────────────────
// Attach bottom sheet widget
// ─────────────────────────────────────────────────────────────────────────────

class _AttachSheet extends StatelessWidget {
  final Future<void> Function(String type) onPicked;
  const _AttachSheet({required this.onPicked});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 36, height: 4,
            margin: const EdgeInsets.only(bottom: 18),
            decoration: BoxDecoration(
              color: const Color(0x1AFFFFFF),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const Align(
            alignment: Alignment.centerLeft,
            child: Text('Attach',
                style: TextStyle(color: _textPri, fontSize: 16,
                    fontWeight: FontWeight.w700, letterSpacing: 0.2)),
          ),
          const SizedBox(height: 14),
          _tile(context, Icons.picture_as_pdf_outlined, _danger,
              'PDF Document', 'Chat about a PDF file', 'pdf'),
          _tile(context, Icons.description_outlined, _accent,
              'Text File', 'TXT, MD, CSV, JSON', 'text'),
          _tile(context, Icons.image_outlined, const Color(0xFF8B5CF6),
              'Image', 'From your gallery', 'image'),
          _tile(context, Icons.attach_file_rounded, const Color(0xFFF59E0B),
              'Other File', 'Any file type', 'file'),
        ],
      ),
    );
  }

  Widget _tile(BuildContext ctx, IconData icon, Color iconColor,
      String title, String subtitle, String type) {
    return GestureDetector(
      onTap: () { Navigator.pop(ctx); onPicked(type); },
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          decoration: BoxDecoration(
            color: const Color(0x0A0A0A),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0x1AFFFFFF), width: 0.5),
          ),
        child: Row(children: [
          Container(
            width: 38, height: 38,
            decoration: BoxDecoration(
              color: iconColor.withOpacity(0.1),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: iconColor, size: 18),
          ),
          const SizedBox(width: 13),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(
                color: _textPri, fontSize: 13, fontWeight: FontWeight.w600)),
            Text(subtitle, style: const TextStyle(color: _textMuted, fontSize: 11)),
          ])),
          const Icon(Icons.chevron_right, color: _textDim, size: 16),
        ]),
      ),
    );
  }
}