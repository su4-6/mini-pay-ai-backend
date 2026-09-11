package co.yixiang.yshop.module.minipay.controller.app;

import static co.yixiang.yshop.framework.common.pojo.CommonResult.success;

import co.yixiang.yshop.framework.apilog.core.annotation.ApiAccessLog;
import co.yixiang.yshop.framework.common.pojo.CommonResult;
import co.yixiang.yshop.module.minipay.service.MiniPayHandoffLoginService;
import co.yixiang.yshop.module.minipay.service.MiniPayHandoffLoginService.LoginView;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/minipay/auth")
public class MiniPayHandoffController {
    private final MiniPayHandoffLoginService logins;

    public MiniPayHandoffController(MiniPayHandoffLoginService logins) {
        this.logins = logins;
    }

    @PostMapping("/handoff")
    @PermitAll
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    public CommonResult<LoginView> handoff(
            @RequestHeader("Origin") String origin,
            @RequestBody HandoffRequest request) {
        return success(logins.login(request.code(), request.deviceProof(), origin));
    }

    public record HandoffRequest(String code, String deviceProof) { }
}
