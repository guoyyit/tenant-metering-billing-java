package com.github.guoyyit.tenantmeteringbillingjava.billing;

import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * Period key formatting and boundary math.
 *
 * <p>All boundaries are evaluated in UTC, making attribution independent of the JVM time
 * zone and fully reproducible:
 * <ul>
 *   <li>DAILY   - key {@code yyyy-MM-dd}, e.g. {@code 2026-09-28}; period runs
 *       {@code 00:00:00 UTC} inclusive to next day {@code 00:00:00 UTC} exclusive.</li>
 *   <li>WEEKLY  - ISO-8601 week key {@code yyyy-Www}, e.g. {@code 2026-W40}; the week
 *       starts Monday and ends the following Monday 00:00 UTC.</li>
 *   <li>MONTHLY - key {@code yyyy-MM}, e.g. {@code 2026-09}; from the first day of the
 *       month 00:00 UTC to the first day of the next month 00:00 UTC.</li>
 * </ul>
 */
public final class PeriodCalculator {

    private static final DateTimeFormatter DAILY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withLocale(Locale.ROOT);
    private static final DateTimeFormatter MONTHLY = DateTimeFormatter.ofPattern("yyyy-MM")
            .withLocale(Locale.ROOT);
    private static final DateTimeFormatter WEEKLY = new DateTimeFormatterBuilder()
            .appendPattern("YYYY-'W'ww")
            .parseDefaulting(WeekFields.ISO.dayOfWeek(), DayOfWeek.MONDAY.getValue())
            .toFormatter(Locale.ROOT);

    private PeriodCalculator() {
    }

    private static LocalDate dateAtUtc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** Period key containing the given instant. */
    public static String periodKey(SettlementCycle cycle, long epochMillis) {
        LocalDate date = dateAtUtc(epochMillis);
        return switch (cycle) {
            case DAILY -> DAILY.format(date);
            case WEEKLY -> WEEKLY.format(date);
            case MONTHLY -> MONTHLY.format(date);
        };
    }

    /** Period key containing the current instant. */
    public static String currentPeriodKey(SettlementCycle cycle) {
        return periodKey(cycle, System.currentTimeMillis());
    }

    /** Inclusive start instant (epoch millis, UTC midnight) of a period. */
    public static long periodStart(SettlementCycle cycle, String periodKey) {
        return startDate(cycle, periodKey).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Exclusive end instant (epoch millis, UTC midnight of the next period) of a period. */
    public static long periodEndExclusive(SettlementCycle cycle, String periodKey) {
        return startDate(cycle, periodKey).plus(1, switch (cycle) {
                    case DAILY -> java.time.temporal.ChronoUnit.DAYS;
                    case WEEKLY -> java.time.temporal.ChronoUnit.WEEKS;
                    case MONTHLY -> java.time.temporal.ChronoUnit.MONTHS;
                })
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Key of the period immediately preceding the given one. */
    public static String previousPeriodKey(SettlementCycle cycle, String periodKey) {
        LocalDate start = startDate(cycle, periodKey).minus(1, switch (cycle) {
            case DAILY -> java.time.temporal.ChronoUnit.DAYS;
            case WEEKLY -> java.time.temporal.ChronoUnit.WEEKS;
            case MONTHLY -> java.time.temporal.ChronoUnit.MONTHS;
        });
        return switch (cycle) {
            case DAILY -> DAILY.format(start);
            case WEEKLY -> WEEKLY.format(start);
            case MONTHLY -> MONTHLY.format(start);
        };
    }

    /** Key of the period immediately following the given one. */
    public static String nextPeriodKey(SettlementCycle cycle, String periodKey) {
        LocalDate start = startDate(cycle, periodKey).plus(1, switch (cycle) {
            case DAILY -> java.time.temporal.ChronoUnit.DAYS;
            case WEEKLY -> java.time.temporal.ChronoUnit.WEEKS;
            case MONTHLY -> java.time.temporal.ChronoUnit.MONTHS;
        });
        return switch (cycle) {
            case DAILY -> DAILY.format(start);
            case WEEKLY -> WEEKLY.format(start);
            case MONTHLY -> MONTHLY.format(start);
        };
    }

    private static LocalDate startDate(SettlementCycle cycle, String periodKey) {
        return switch (cycle) {
            case DAILY -> LocalDate.parse(periodKey, DAILY);
            case WEEKLY -> LocalDate.parse(periodKey, WEEKLY);
            case MONTHLY -> LocalDate.parse(periodKey + "-01", DAILY);
        };
    }
}
