<html lang="${locale}">
<body style="font-family: sans-serif; line-height: 1.5;">
<p>Здравствуйте<#if recipient.displayName??>, ${recipient.displayName}</#if>!</p>

<p>Кто-то запросил сброс пароля для вашей учётной записи. Если это были вы, перейдите по ссылке ниже.</p>

<p><a href="${resetLink}">Сбросить пароль</a></p>

<p>Ссылка действует ${expiresInMinutes} мин. Если вы ничего не запрашивали, просто проигнорируйте это
    письмо - пароль не изменился.</p>
</body>
</html>
