<#--
  An announcement template, rendered once per supported locale at publish rather than once per
  recipient. It therefore has no `recipient` in its model: an announcement is one document for an
  audience, so there is nobody to greet by name - and a template that referenced one would fail
  strict rendering at publish, which is the right moment to find out.
-->
<p>${productName} ${version} is now available. See the release notes for what changed.</p>
