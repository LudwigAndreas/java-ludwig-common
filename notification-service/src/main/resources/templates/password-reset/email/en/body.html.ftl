<#--
  A transactional template. Note that ${resetLink} and ${expiresInMinutes} carry no `!` default:
  if the caller does not send them the render fails loudly and the delivery goes DEAD, rather than
  an email going out that says "click  to reset, the link expires in  minutes".

  ${recipient.displayName!} does carry one, because the identity projection legitimately may not
  know this user yet - see DefaultRecipientResolver - and a greeting is worth degrading.
-->
<html lang="${locale}">
<body style="font-family: sans-serif; line-height: 1.5;">
<p>Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,</p>

<p>Someone asked to reset the password for your account. If that was you, use the link below.</p>

<p><a href="${resetLink}">Reset your password</a></p>

<p>The link expires in ${expiresInMinutes} minutes. If you did not ask for this, you can ignore this
    message - your password has not changed.</p>
</body>
</html>
