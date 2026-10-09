<#--
  The in-app channel renders all three parts. Unlike chat, an inbox item has a title the recipient
  reads in a list before deciding to open it, so the subject is not optional here; and unlike a
  webhook, a person reads this, so there is a plain-text alternative for a client that renders its
  own markup rather than this HTML.

  The directory segment is `in_app`, matching the enum constant, while the user-settings opt-out key
  for the same channel is `in-app`. The two differ on purpose: a setting key is constrained by a
  pattern that admits no underscore, and a directory name is not constrained at all. Do not "fix"
  either to match the other - see NotificationSettings#keySegment.
-->
<p>Welcome to ${productName}, ${recipient.displayName!"there"}. Your account is ready.</p>
