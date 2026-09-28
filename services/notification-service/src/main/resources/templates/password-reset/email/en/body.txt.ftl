<#--
  The plain-text alternative. Always worth writing: a multipart/alternative message with no text
  part scores badly with every mailbox provider's spam filter, and that cost is paid by every other
  recipient on the same sending domain.
-->
Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,

Someone asked to reset the password for your account. If that was you, open this link:

${resetLink}

The link expires in ${expiresInMinutes} minutes. If you did not ask for this, you can ignore this
message - your password has not changed.
