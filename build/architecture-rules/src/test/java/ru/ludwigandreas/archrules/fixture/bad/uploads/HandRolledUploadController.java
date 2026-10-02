package ru.ludwigandreas.archrules.fixture.bad.uploads;

import org.springframework.web.multipart.MultipartFile;

/**
 * The endpoint {@code RuleGroup.UPLOADS} exists to catch: a service taking a multipart itself.
 *
 * <p>Every mistake the rule's javadoc predicts is in these four lines - no ceiling before the body is read,
 * {@code getBytes()} putting the whole file in the heap, and nothing that would produce a row-level error report.
 */
public class HandRolledUploadController {

    /**
     * Takes an upload by hand.
     *
     * @param file the upload
     * @return how many bytes it was
     */
    public int importOrders(MultipartFile file) {
        try {
            return file.getBytes().length;
        } catch (java.io.IOException failed) {
            return 0;
        }
    }
}
