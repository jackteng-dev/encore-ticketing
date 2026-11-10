package dev.jackteng.encore.common;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice
public class ApiErrors {
 public record Error(String code,String message) {}
 public static class Problem extends RuntimeException {
  public final int status; public final String code;public final Integer retryAfterSeconds;
  public Problem(int status,String code,String message) {this(status,code,message,null);}
  public Problem(int status,String code,String message,Integer retryAfterSeconds) {super(message);this.status=status;this.code=code;this.retryAfterSeconds=retryAfterSeconds;}
 }
 @ExceptionHandler(Problem.class) ResponseEntity<Error> problem(Problem e) {
  var response=ResponseEntity.status(e.status);if(e.retryAfterSeconds!=null)response.header("Retry-After",e.retryAfterSeconds.toString());
  return response.body(new Error(e.code,e.getMessage()));
 }
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
  ConstraintViolationException.class,HandlerMethodValidationException.class,MethodArgumentTypeMismatchException.class})
 ResponseEntity<Error> invalid(Exception e) {return ResponseEntity.badRequest().body(new Error("INVALID_INPUT","欄位格式或內容不正確"));}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Error> conflict(Exception e) {
  return ResponseEntity.status(409).body(new Error("CONFLICT","資料衝突，請重新核對"));
 }
}
