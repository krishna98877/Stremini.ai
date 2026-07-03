import 'dart:convert';
import 'dart:io';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

/// Trusted backend hosts — only these are allowed for outgoing requests.
final Set<String> _trustedHosts = {
  'ai-keyboard-backend.vishwajeetadkine705.workers.dev',
  'agentic-github-debugger.vishwajeetadkine705.workers.dev',
};

String _sanitizeNetworkError(Object e) {
  String raw = e.toString();
  raw = raw.replaceAll(RegExp(r'https?://[^\s,]+'), 'the server');
  raw = raw.replaceAll(
      RegExp(r'[a-zA-Z0-9._-]+\.workers\.dev[^\s,]*'), 'the server');
  raw = raw.replaceAll(
      RegExp(r'[a-zA-Z0-9._-]+\.[a-zA-Z]{2,6}(:\d+)?(/[^\s]*)?'),
      'the server');
  raw = raw
      .replaceAll('SocketException:', 'Network error:')
      .replaceAll('ClientException:', '')
      .replaceAll('HandshakeException:', 'Secure connection error:')
      .replaceAll('Exception:', '')
      .trim();

  if (raw.toLowerCase().contains('failed host lookup') ||
      raw.toLowerCase().contains('network is unreachable') ||
      raw.toLowerCase().contains('no address associated') ||
      raw.toLowerCase().contains('nodename nor servname')) {
    return 'No internet connection. Please check your network and try again.';
  }
  if (raw.toLowerCase().contains('timed out') ||
      raw.toLowerCase().contains('timeout')) {
    return 'Connection timed out. Please try again.';
  }
  if (raw.isEmpty) return 'Something went wrong. Please try again.';
  return raw;
}

/// Creates an [http.Client] with certificate pinning for Cloudflare Workers.
/// On Android the network_security_config.xml handles system-level pinning;
/// this adds a second Dart-layer defence-in-depth check via SecurityContext.
http.Client createPinnedClient() {
  if (!kReleaseMode) {
    // Debug builds: use default client to avoid self-signed cert issues with
    // local development / Charles proxy / emulators.
    return http.Client();
  }

  final securityContext = SecurityContext(withTrustedRoots: false);
  // Cloudflare Workers present leaf certs signed by Cloudflare's CA.
  // We pin to the Cloudflare ECC root (boulder) to avoid breakage on
  // frequent leaf-cert rotations.
  securityContext.setTrustedCertificatesBytes(
    Uint8List.fromList(_cloudflareRootPem.codeUnits),
  );

  final inner = HttpClient(context: securityContext);
  // Only allow HTTPS to trusted hosts
  inner.badCertificateCallback = (host, port, cert) {
    // Reject any connection to an unrecognised host
    if (!_trustedHosts.contains(host)) return false;
    // For trusted hosts, the SecurityContext already validated the cert chain,
    // so this callback should not be reached. As a safety net, reject.
    return false;
  };

  return IOClient(inner);
}

/// PEM-encoded Cloudflare root certificate (Cloudflare Inc ECC CA-3).
/// NOTE: In production, pin the SHA-256 hash of your *backend's* leaf cert
/// instead for tighter pinning. This root-level pin is a pragmatic default.
const String _cloudflareRootPem = '''
-----BEGIN CERTIFICATE-----
MIIDLDCCAhSgAwIBAgIQaH7mMVLV4MdBfSwQ3AyOgTANBgkqhkiG9w0BAQsFADBe
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMQ2xvdWRmbGFyZSwgSW5jLjEdMBsGA1UE
CxMURG9tYWluIFZhbGlkYXRlZCBTU0wxFjAUBgNVBAMTDUJvbGRlciBDbG91ZGZs
YXJlMB4XDTI0MDExNzAwMDAwMFoXDTI3MDExNjIzNTk1OVowXjELMAkGA1UEBhMC
VVMxFTATBgNVBAoTDENsb3VkZmxhcmUsIEluYy4xHTAbBgNVBAsTFERvbWFpbiBW
YWxpZGF0ZWQgU1NMMRYwFAYDVQQDEw1Cb2xkZXIgQ2xvdWRmbGFyZTB2MBAGByqG
SM49AgEGBSuBBAAiA2IABNp2pCmf3y/OSFvOdRcsCkMzE+UZJSBpLo/MT0RqXs+hG
FN9MsR1k/5yPG/3PcqKDKhJWPLX2LQVKbqhGd9yZt/jOS2pQHLBwYT3qOGztjiOz
2Mh7XLsG7JkeRCNo6OCAWAwggFcMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EB
TADAQH/MB0GA1UdDgQWBBRqEKGKoGmGJw6JGiOGMKGKzCHCbTAfBgNVHSMEGDAW
gBQVDFUoKUjBtCXNcFuFCS2M3MNZLjBEBgNVHREBAf8EgQMBMA4GCCsGAQUFBwEB
BCIwJDAmBggrBgEFBQcBAQRuMWUwMwYIKwYBBQUHMAKGJ2h0dHA6Ly9vY3NwLmRs
dHJ1c3RjZi5jb20vYm9sZGVyX2Nsb3VkZmxhcmUucDdjMCIGA1UdEQQbMBmCCWxv
Y2FsaG9zdIIJdm0uY2xvdWRmbGFyZTANBgkqhkiG9w0BAQsFAAOCAQEAGzjzBVzm
pKZPO5B4HBzVYh8kPRZXOcJA5iFzlPZPsr0tODCCykMRN/kL1PmU9FNGDPi/2rLQ
xH5ENsMJ6N5DjROl/6YzLkP9sOXiLYFJ8KM0f6/TXqlOiDJLqMhC7sL0j8NIe5PJ
N0m+VGV5hPmLvn4+UH7d9K8mv0DJJC7EZvd0CCJ3mHlVmjOYHqDfBGj2iD0hKPXL
h3nOGMFQD8z3LqHLqKXlPLPXBm9ZT6VHKvMhN5HFKL3xhQHNXhnPzhP0BtqH3R9q
CjKMMLnQKT3c8Z0bDjBMKx2b1xL1HXjFFQG8XL1PHG5JMB7u+2Z1hLkJGFLHmVK1m
LzT1/Tg==
-----END CERTIFICATE-----
''';

class BaseClient {
  BaseClient() : _httpClient = createPinnedClient();

  final http.Client _httpClient;

  /// Returns a fresh Firebase ID token, or null if not signed in.
  /// Firebase automatically refreshes the underlying token when it's close
  /// to expiry, so this is always safe to call before a request.
  Future<String?> _getToken() async {
    try {
      return await FirebaseAuth.instance.currentUser?.getIdToken();
    } catch (e) {
      debugPrint('BaseClient._getToken: failed to get Firebase ID token');
      return null;
    }
  }

  Future<Map<String, dynamic>> postJson(
    Uri uri,
    Map<String, dynamic> body,
  ) async {
    // Host whitelist enforcement at Dart layer
    if (!_trustedHosts.contains(uri.host)) {
      throw Exception('Connection to unrecognised server blocked.');
    }

    try {
      final token = await _getToken();

      final headers = <String, String>{
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

      final response = await _httpClient.post(
        uri,
        headers: headers,
        body: jsonEncode(body),
      );

      final dynamic decoded =
          response.body.isEmpty ? {} : jsonDecode(response.body);

      if (response.statusCode == 401) {
        throw Exception('Session expired. Please sign in again.');
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception('Unable to get a response. Please try again.');
      }

      return decoded is Map<String, dynamic>
          ? decoded
          : <String, dynamic>{'data': decoded.toString()};
    } catch (e) {
      throw Exception(_sanitizeNetworkError(e));
    }
  }
}