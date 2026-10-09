package ru.ludwigandreas.notification.service.model;

/**
 * What the recipient is allowed to decline about this category, in two independent answers.
 *
 * <p>The distinction that makes opt-out implementable: a recipient who unsubscribed from marketing
 * must still get their password reset, and a single on/off switch cannot express that.
 *
 * <h2>Two questions, not one</h2>
 *
 * <p>This used to be one question - bypass or not - and {@link #TRANSACTIONAL} answered it for both
 * opt-out and quiet hours at once. That was enough while there were two classes, and
 * {@link #PLATFORM} is the first that needs them separated:
 *
 * <table border="1">
 *   <caption>What each class bypasses</caption>
 *   <tr><th>class</th><th>per-category opt-out</th><th>quiet hours</th></tr>
 *   <tr><td>{@code TRANSACTIONAL}</td><td>bypassed</td><td>bypassed</td></tr>
 *   <tr><td>{@code PLATFORM}</td><td>bypassed</td><td><b>honoured</b></td></tr>
 *   <tr><td>{@code MARKETING}</td><td>honoured</td><td>honoured</td></tr>
 * </table>
 *
 * <p>The empty cell in the old two-class model is the whole argument for adding one. Forced into
 * {@code TRANSACTIONAL}, a release note emails somebody at three in the morning; forced into
 * {@code MARKETING}, half the estate has opted out of an incident notice. Neither is a wording
 * problem that documentation could fix.
 *
 * <p>Both answers are <b>mandatory constructor arguments</b>, so a fourth class cannot be added
 * without somebody deciding both. That is a stronger check than any rule in the enforcement triad,
 * and it is the reason they are not a lookup table or a {@code Set} somewhere else - either would
 * have defaulted silently, and the default would be whichever the author of the table happened to
 * pick.
 *
 * <p><b>No build can check that a category's class is the right answer.</b> It is a statement about
 * what the organisation may impose on people, exactly the shape of a cache's {@code CachePurpose}.
 * What is checked is that the catalogue names a class that exists, at startup rather than at the
 * moment somebody is trying to announce something; and the mitigation for the rest is that the
 * catalogue is configuration owned by whoever owns the notification catalogue, not a field a calling
 * service can set per request.
 *
 * <p>The suppression list has no bypass at any class, and that is deliberate and unchanged: a hard
 * bounce is a fact about an address rather than a wish of its owner.
 */
public enum CategoryClass {

    /**
     * The recipient asked for this, directly or by using the product: receipts, password resets,
     * security alerts, expiry warnings.
     *
     * <p>Bypasses quiet hours as well as opt-out, because the recipient is waiting for it - a
     * one-time code deferred to the morning is not a notification, it is a failure.
     */
    TRANSACTIONAL(true, true),

    /**
     * The platform is telling the recipient something they cannot decline but which is not urgent:
     * a release note, a deprecation warning, an incident notice.
     *
     * <p>Bypasses opt-out because the organisation has decided they should know. Honours quiet hours
     * because nothing is waiting on it - there is no reason this cannot arrive at eight in the
     * morning instead of at three.
     *
     * <p>Note that quiet hours only ever applied to an interrupting channel in the first place, so
     * for a {@code PLATFORM} announcement shown in the inbox this answer changes nothing; it decides
     * what happens to the <em>email</em> half of the same announcement.
     */
    PLATFORM(true, false),

    /** Anything the recipient can decline: campaigns, newsletters, product announcements. */
    MARKETING(false, false);

    private final boolean bypassesOptOut;
    private final boolean bypassesQuietHours;

    CategoryClass(boolean bypassesOptOut, boolean bypassesQuietHours) {
        this.bypassesOptOut = bypassesOptOut;
        this.bypassesQuietHours = bypassesQuietHours;
    }

    /** Whether a recipient's per-category opt-out is ignored for this class. */
    public boolean bypassesOptOut() {
        return bypassesOptOut;
    }

    /**
     * Whether the recipient's quiet window is ignored for this class.
     *
     * <p>Asked separately from {@link #bypassesOptOut()} rather than derived from it. The two were
     * one answer until {@code PLATFORM} existed, and collapsing them again is how a release note
     * starts arriving at three in the morning.
     */
    public boolean bypassesQuietHours() {
        return bypassesQuietHours;
    }
}
