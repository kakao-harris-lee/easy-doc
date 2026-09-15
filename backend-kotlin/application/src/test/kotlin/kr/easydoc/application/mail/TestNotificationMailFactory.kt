package kr.easydoc.application.mail

/** 유스케이스 테스트는 문구 대신 알림 종류·전달값을 관찰한다. 기본 문구는 infrastructure에서 검증한다. */
object TestNotificationMailFactory : NotificationMailFactory {
    override fun create(
        type: NotificationType,
        recipient: EmailAddress,
        parameters: Map<String, String>,
    ): OutboundMail = OutboundMail(recipient, type.name, parameters.values.joinToString("\n"))
}
