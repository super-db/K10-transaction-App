package com.k10.smsbridge.sync
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ManualRefreshRangeTest{
    private fun epoch(value:String)=Instant.parse(value).toEpochMilli()
    @Test fun rangeBoundariesUseIndiaMidnightAndIncludeToday(){
        val now=epoch("2026-11-05T14:00:00Z")
        val today=ManualRefreshRange.create("today",now)
        assertEquals(epoch("2026-11-04T18:30:00Z"),today.from)
        assertTrue(today.contains(today.from));assertFalse(today.contains(today.from-1))
        assertTrue(today.contains(now));assertFalse(today.contains(now+1))
        assertEquals(epoch("2026-10-21T18:30:00Z"),ManualRefreshRange.create("15d",now).from)
        assertEquals(epoch("2026-10-06T18:30:00Z"),ManualRefreshRange.create("30d",now).from)
    }
    @Test fun allVerifiesHistoryWithoutScanningInbox(){
        val range=ManualRefreshRange.create("all",epoch("2026-11-05T14:00:00Z"))
        assertFalse(range.scansInbox);assertEquals(0L,range.from)
        assertTrue(ManualRefreshRange.create("today").scansInbox)
    }
    @Test(expected=IllegalArgumentException::class)fun unknownRangeIsRejected(){ManualRefreshRange.create("bad")}
}
