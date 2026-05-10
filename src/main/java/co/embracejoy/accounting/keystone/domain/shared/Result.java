package co.embracejoy.accounting.keystone.domain.shared;

import java.util.Objects;
import java.util.function.Function;

/**
 * A success-or-failure value used at internal API boundaries.
 *
 * <p>Reserved for expected, recoverable outcomes (validation, business-rule violations). True bugs
 * (NPE, IO crashes) still throw.
 *
 * @param <T> the success value type
 * @param <E> the error type
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

  static <T, E> Result<T, E> success(T value) {
    return new Success<>(value);
  }

  static <T, E> Result<T, E> failure(E error) {
    return new Failure<>(error);
  }

  <U> Result<U, E> map(Function<? super T, ? extends U> mapper);

  <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper);

  <R> R fold(
      Function<? super T, ? extends R> onSuccess, Function<? super E, ? extends R> onFailure);

  record Success<T, E>(T value) implements Result<T, E> {
    @Override
    public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
      Objects.requireNonNull(mapper, "mapper");
      return new Success<>(mapper.apply(value));
    }

    @Override
    public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
      Objects.requireNonNull(mapper, "mapper");
      return mapper.apply(value);
    }

    @Override
    public <R> R fold(
        Function<? super T, ? extends R> onSuccess, Function<? super E, ? extends R> onFailure) {
      Objects.requireNonNull(onSuccess, "onSuccess");
      return onSuccess.apply(value);
    }
  }

  record Failure<T, E>(E error) implements Result<T, E> {
    @Override
    @SuppressWarnings("unchecked")
    public <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
      Objects.requireNonNull(mapper, "mapper");
      return (Result<U, E>) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> mapper) {
      Objects.requireNonNull(mapper, "mapper");
      return (Result<U, E>) this;
    }

    @Override
    public <R> R fold(
        Function<? super T, ? extends R> onSuccess, Function<? super E, ? extends R> onFailure) {
      Objects.requireNonNull(onFailure, "onFailure");
      return onFailure.apply(error);
    }
  }
}
