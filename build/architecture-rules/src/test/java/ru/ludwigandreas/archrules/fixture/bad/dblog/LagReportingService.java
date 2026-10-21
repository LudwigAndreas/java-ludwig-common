package ru.ludwigandreas.archrules.fixture.bad.dblog;

import ru.ludwigandreas.messaging.db.fixture.DbLogApi;

/** Reaches into the DB-log transport directly, so the day it is removed this class stops compiling. */
public class LagReportingService {

    private final DbLogApi log = new DbLogApi();

    /**
     * Reports the lag.
     *
     * @return the lag
     */
    public long lag() {
        return log.lag();
    }
}
