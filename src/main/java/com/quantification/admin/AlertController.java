package com.quantification.admin;

import com.quantification.service.MailAlertService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警的调试入口。
 *
 * <p>只提供一个"发测试邮件"的 POST，用来在配好 SMTP 后立刻验证授权码 / 服务器 / 端口
 * 是否可用——发信失败只会写日志、不会报错，所以要用这个接口确认到底发没发出去。
 */
@RestController
@RequestMapping("/api/admin/alert")
public class AlertController {

    /** 邮件告警服务。 */
    private final MailAlertService mailAlert;

    /**
     * @param mailAlert 邮件告警服务
     */
    public AlertController(MailAlertService mailAlert) {
        this.mailAlert = mailAlert;
    }

    /**
     * 发一封测试告警邮件（验证 SMTP 配置用）。
     *
     * @return 说明文本
     */
    @PostMapping("/test")
    public String test() {
        mailAlert.send("测试告警", "这是一封来自 quantification 的测试告警邮件。收到即说明 SMTP 配置正确。");
        return "已尝试发送测试告警邮件，请查看收件箱；"
                + "若几分钟后仍没收到，看启动日志确认 alert.mail 配置是否完整（enabled / username / to / 外置密码）。";
    }
}
