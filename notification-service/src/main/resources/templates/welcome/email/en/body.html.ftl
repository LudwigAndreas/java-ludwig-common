<html lang="${locale}">
<body style="font-family: sans-serif; line-height: 1.5;">
<p>Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,</p>

<p>Your ${productName} account is ready.</p>

<#-- An optional section: the `??` test is how a template opts into a variable being absent, which
     is the only way to do it here - the renderer is strict by default. -->
<#if gettingStartedLink??>
    <p><a href="${gettingStartedLink}">Start here</a></p>
</#if>
</body>
</html>
