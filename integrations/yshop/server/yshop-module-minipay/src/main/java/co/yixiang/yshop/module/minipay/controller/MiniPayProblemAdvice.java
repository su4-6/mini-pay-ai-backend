package co.yixiang.yshop.module.minipay.controller;

import co.yixiang.yshop.module.minipay.controller.app.MiniPayH5FoodController;
import co.yixiang.yshop.module.minipay.controller.app.MiniPayHandoffController;
import co.yixiang.yshop.module.minipay.service.MiniPayProblem;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        MiniPayInternalController.class,
        MiniPayH5FoodController.class,
        MiniPayHandoffController.class
})
public class MiniPayProblemAdvice {
    @ExceptionHandler(MiniPayProblem.class)
    ResponseEntity<Map<String, Object>> handle(MiniPayProblem problem) {
        return ResponseEntity.status(problem.status()).body(Map.of(
                "code", problem.code(),
                "status", problem.status().value(),
                "timestamp", Instant.now().toString()));
    }
}
