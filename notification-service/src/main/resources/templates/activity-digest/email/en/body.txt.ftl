Hello<#if recipient.displayName??> ${recipient.displayName}</#if>,

Here is what happened since we last wrote:

<#list items as item>
- ${item.summary!"(no summary)"}
</#list>
