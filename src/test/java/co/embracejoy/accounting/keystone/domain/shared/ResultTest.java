package co.embracejoy.accounting.keystone.domain.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Result")
class ResultTest {

  @Test
  @DisplayName("success() wraps a value in a Success")
  void shouldWrapValueInSuccessWhenSuccessFactoryUsed() {
    Result<Integer, String> r = Result.success(42);
    assertInstanceOf(Result.Success.class, r);
    assertEquals(42, ((Result.Success<Integer, String>) r).value());
  }

  @Test
  @DisplayName("failure() wraps an error in a Failure")
  void shouldWrapErrorInFailureWhenFailureFactoryUsed() {
    Result<Integer, String> r = Result.failure("boom");
    assertInstanceOf(Result.Failure.class, r);
    assertEquals("boom", ((Result.Failure<Integer, String>) r).error());
  }

  @Test
  @DisplayName("map transforms the value of a Success")
  void shouldTransformValueWhenMapAppliedToSuccess() {
    Result<Integer, String> r = Result.<Integer, String>success(2).map(x -> x * 3);
    assertEquals(6, ((Result.Success<Integer, String>) r).value());
  }

  @Test
  @DisplayName("map leaves a Failure untouched")
  void shouldReturnSameFailureWhenMapAppliedToFailure() {
    Result<Integer, String> failure = Result.failure("nope");
    Result<Integer, String> mapped = failure.map(x -> x * 3);
    assertSame(failure, mapped);
  }

  @Test
  @DisplayName("flatMap chains Success into another Result")
  void shouldChainResultsWhenFlatMapAppliedToSuccess() {
    Result<Integer, String> r =
        Result.<Integer, String>success(2).flatMap(x -> Result.success(x + 1));
    assertEquals(3, ((Result.Success<Integer, String>) r).value());
  }

  @Test
  @DisplayName("flatMap short-circuits on Failure")
  void shouldShortCircuitWhenFlatMapAppliedToFailure() {
    Result<Integer, String> failure = Result.failure("stop");
    Result<Integer, String> chained = failure.flatMap(x -> Result.success(x + 1));
    assertSame(failure, chained);
  }

  @Test
  @DisplayName("fold applies the success branch on Success")
  void shouldApplySuccessBranchWhenFoldAppliedToSuccess() {
    String s = Result.<Integer, String>success(7).fold(v -> "ok:" + v, e -> "err:" + e);
    assertEquals("ok:7", s);
  }

  @Test
  @DisplayName("fold applies the error branch on Failure")
  void shouldApplyErrorBranchWhenFoldAppliedToFailure() {
    String s = Result.<Integer, String>failure("bad").fold(v -> "ok:" + v, e -> "err:" + e);
    assertEquals("err:bad", s);
  }

  @Test
  @DisplayName("Result is sealed and only Success and Failure may implement it")
  void shouldOnlyPermitSuccessAndFailureAsImplementations() {
    assertEquals(2, Result.class.getPermittedSubclasses().length);
  }

  @Test
  @DisplayName("Failure.map rejects null mapper")
  void shouldThrowWhenFailureMapPassedNullFunction() {
    assertThrows(NullPointerException.class, () -> Result.<Integer, String>failure("e").map(null));
  }

  @Test
  @DisplayName("Success.flatMap rejects null mapper")
  void shouldThrowWhenSuccessFlatMapPassedNullFunction() {
    assertThrows(
        NullPointerException.class, () -> Result.<Integer, String>success(1).flatMap(null));
  }

  @Test
  @DisplayName("Failure.flatMap rejects null mapper")
  void shouldThrowWhenFailureFlatMapPassedNullFunction() {
    assertThrows(
        NullPointerException.class, () -> Result.<Integer, String>failure("e").flatMap(null));
  }

  @Test
  @DisplayName("fold rejects null onSuccess on Success")
  void shouldThrowWhenSuccessFoldPassedNullOnSuccess() {
    assertThrows(
        NullPointerException.class, () -> Result.<Integer, String>success(1).fold(null, e -> "e"));
  }

  @Test
  @DisplayName("fold rejects null onFailure on Success")
  void shouldThrowWhenSuccessFoldPassedNullOnFailure() {
    assertThrows(
        NullPointerException.class, () -> Result.<Integer, String>success(1).fold(v -> "v", null));
  }

  @Test
  @DisplayName("fold rejects null onSuccess on Failure")
  void shouldThrowWhenFailureFoldPassedNullOnSuccess() {
    assertThrows(
        NullPointerException.class,
        () -> Result.<Integer, String>failure("e").fold(null, e -> "e"));
  }

  @Test
  @DisplayName("fold rejects null onFailure on Failure")
  void shouldThrowWhenFailureFoldPassedNullOnFailure() {
    assertThrows(
        NullPointerException.class,
        () -> Result.<Integer, String>failure("e").fold(v -> "v", null));
  }

  @Test
  @DisplayName("success rejects null mapper")
  void shouldThrowWhenMapPassedNullFunction() {
    assertThrows(NullPointerException.class, () -> Result.success(1).map(null));
  }
}
