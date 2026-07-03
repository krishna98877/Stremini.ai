sealed class Result<T> {
  const Result();

  R when<R>({
    required R Function(T value) success,
    required R Function(Failure failure) failure,
  });
}

class Success<T> extends Result<T> {
  const Success(this.value);
  final T value;

  @override
  R when<R>({required R Function(T value) success, required R Function(Failure failure) failure}) {
    return success(value);
  }
}

class Error<T> extends Result<T> {
  const Error(this.failureValue);
  final Failure failureValue;

  @override
  R when<R>({required R Function(T value) success, required R Function(Failure failure) failure}) {
    return failure(failureValue);
  }
}

sealed class Failure {
  const Failure(this.message);
  final String message;
}

class NetworkFailure extends Failure {
  const NetworkFailure(super.message);
}

class ServerFailure extends Failure {
  const ServerFailure(super.message);
}

class UnknownFailure extends Failure {
  const UnknownFailure(super.message);
}
