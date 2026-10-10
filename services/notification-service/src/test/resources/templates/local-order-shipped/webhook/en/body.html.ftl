<#--
  A webhook template for the local run and its tests, and a TEST resource for that reason. The
  service ships no template with a webhook variant - a deployment mounts its own - so without this
  one the webhook channel would have nothing to render locally and every webhook delivery would die
  on "template not found" before reaching the stand-in.
-->
<p>Order ${orderId} has shipped.</p>
