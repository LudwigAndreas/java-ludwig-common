<#--
  A digest template. It iterates `items` - one entry per collapsed delivery, carrying that
  delivery's own variables - which is what lets one template summarise notifications this service
  has never seen the shape of. See DigestCollapseService.
-->
<html lang="${locale}">
<body style="font-family: sans-serif; line-height: 1.5;">
<p>Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,</p>

<p>Here is what happened since we last wrote:</p>

<ul>
    <#list items as item>
        <li>${item.summary!"(no summary)"}</li>
    </#list>
</ul>
</body>
</html>
