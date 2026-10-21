package ru.ludwigandreas.messaging.db.fixture;

/**
 * Stands in for a class of messaging-db-spring-boot-starter, which this module cannot depend on: that module
 * depends on this one. The removability rule matches by package, so a class in the package is all it needs.
 */
public class DbLogApi {

    /**
     * Something a service might be tempted to call.
     *
     * @return a lag figure
     */
    public long lag() {
        return 0;
    }
}
