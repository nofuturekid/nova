package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.DiskStatus
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.graphql.GetArrayQuery
import io.github.nofuturekid.nova.graphql.type.ArrayDiskStatus
import io.github.nofuturekid.nova.graphql.type.ArrayDiskType
import io.github.nofuturekid.nova.graphql.type.ArrayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [GetArrayQuery.Data.toArrayInfo] carries the enriched disk
 * fields introduced in v0.1.40-beta8 (ADR-0045).
 *
 * WHY: the root bug was temp=null/0 displayed as "0°" for a sleeping disk.
 * These tests pin that:
 * 1. isSpinning=false + temp=null → tempC=0 AND isSpinning=false in the domain
 *    model (so the UI can gate display correctly).
 * 2. numErrors is carried through unchanged (disk errors must surface).
 * 3. Per-disk warning/critical thresholds are carried through (null stays null
 *    when unset; positive values are mapped; zero/negative → null / "unset").
 */
class ArrayMappingTest {

    // ── Sleeping disk ─────────────────────────────────────────────────

    @Test fun sleeping_disk_has_isSpinning_false_and_tempC_zero() {
        // WHY: the primary 0.1.40-beta8 bug: a spun-down disk sends temp=null.
        // The mapper coalesces null→0 for tempC. isSpinning=false MUST be
        // faithfully forwarded so the UI can show "Standby" rather than "0°".
        val data = arrayData(
            disks = listOf(disk(name = "disk1", temp = null, isSpinning = false)),
        )
        val info = data.toArrayInfo()
        val d = info.disks.first { it.type == DiskType.Data }
        assertFalse("sleeping disk must have isSpinning=false", d.isSpinning)
        assertEquals("temp=null must coalesce to 0", 0, d.tempC)
    }

    @Test fun spinning_disk_with_temp_carries_through() {
        // WHY: normal spinning disk — temp must not be dropped.
        val data = arrayData(
            disks = listOf(disk(name = "disk2", temp = 38, isSpinning = true)),
        )
        val info = data.toArrayInfo()
        val d = info.disks.first { it.type == DiskType.Data }
        assertTrue(d.isSpinning)
        assertEquals(38, d.tempC)
    }

    // ── numErrors ─────────────────────────────────────────────────────

    @Test fun num_errors_carried_through() {
        // WHY: disk errors must be surfaced — they are a critical health signal.
        val data = arrayData(
            disks = listOf(disk(name = "disk3", numErrors = 3L)),
        )
        val info = data.toArrayInfo()
        val d = info.disks.first { it.type == DiskType.Data }
        assertEquals(3L, d.numErrors)
    }

    @Test fun null_num_errors_defaults_to_zero() {
        val data = arrayData(disks = listOf(disk(name = "disk4", numErrors = null)))
        val d = data.toArrayInfo().disks.first { it.type == DiskType.Data }
        assertEquals(0L, d.numErrors)
    }

    // ── Per-disk thresholds ───────────────────────────────────────────

    @Test fun positive_warning_and_critical_carried_through() {
        // WHY: when Unraid has per-disk thresholds set, the mapper must forward
        // them so the UI can use server values instead of app defaults.
        val data = arrayData(
            disks = listOf(disk(name = "disk5", warningC = 45, criticalC = 55)),
        )
        val d = data.toArrayInfo().disks.first { it.type == DiskType.Data }
        assertEquals(45, d.warningC)
        assertEquals(55, d.criticalC)
    }

    @Test fun null_thresholds_stay_null() {
        // WHY: null thresholds mean "unset" — the UI falls back to app defaults.
        val data = arrayData(
            disks = listOf(disk(name = "disk6", warningC = null, criticalC = null)),
        )
        val d = data.toArrayInfo().disks.first { it.type == DiskType.Data }
        assertNull(d.warningC)
        assertNull(d.criticalC)
    }

    @Test fun zero_thresholds_treated_as_unset_null() {
        // WHY: Unraid sometimes returns 0 for an unset threshold; <=0 must
        // be treated as "not configured" so the UI falls back to app defaults,
        // not fires a threshold at 0 °C.
        val data = arrayData(
            disks = listOf(disk(name = "disk7", warningC = 0, criticalC = 0)),
        )
        val d = data.toArrayInfo().disks.first { it.type == DiskType.Data }
        assertNull("warningC=0 must be treated as unset", d.warningC)
        assertNull("criticalC=0 must be treated as unset", d.criticalC)
    }

    // ── Parity disk (no fsUsed) ───────────────────────────────────────

    @Test fun parity_sleeping_disk_isSpinning_false() {
        // WHY: parity disks can also spin down; the mapper passes usedKb=null
        // for parities (no filesystem) but must still forward isSpinning.
        val data = arrayData(
            parities = listOf(parity(name = "parity1", temp = null, isSpinning = false)),
        )
        val p = data.toArrayInfo().disks.first { it.type == DiskType.Parity }
        assertFalse(p.isSpinning)
        assertEquals(0, p.tempC)
    }

    // ── Status ────────────────────────────────────────────────────────

    @Test fun errored_status_maps_to_disk_error() {
        val data = arrayData(
            disks = listOf(disk(name = "disk8", status = ArrayDiskStatus.DISK_INVALID)),
        )
        val d = data.toArrayInfo().disks.first { it.type == DiskType.Data }
        assertEquals(DiskStatus.Error, d.status)
    }

    // ── Pool member (ZFS mirror / secondary member) ───────────────────

    @Test fun cache_disk_with_null_fsSize_is_pool_member() {
        // WHY: a ZFS mirror pool's second device has fsSize=null because the
        // pool filesystem stats live only on the first member. Marking it as
        // isPoolMember=true prevents the UI from rendering it as an "empty"
        // disk with a misleading 0% usage bar.
        val data = arrayData(
            caches = listOf(cache(name = "cache2", fsSize = null, fsUsed = null)),
        )
        val d = data.toArrayInfo().disks.first { it.name == "cache2" }
        assertTrue("ZFS mirror member with fsSize=null must be isPoolMember=true", d.isPoolMember)
    }

    @Test fun cache_disk_with_fsSize_present_is_not_pool_member() {
        // WHY: the first/primary cache member has a real filesystem and must
        // show its usage bar normally — isPoolMember must be false.
        val data = arrayData(
            caches = listOf(cache(name = "cache", fsSize = 4_000_000_000L, fsUsed = 1_000_000_000L)),
        )
        val d = data.toArrayInfo().disks.first { it.name == "cache" }
        assertFalse("cache disk with fsSize present must NOT be isPoolMember", d.isPoolMember)
    }

    @Test fun data_disk_with_fsSize_present_is_not_pool_member() {
        // WHY: regular array data disks have their own filesystem and must
        // never be treated as pool members.
        val data = arrayData(
            disks = listOf(disk(name = "disk1")),
        )
        val d = data.toArrayInfo().disks.first { it.name == "disk1" }
        assertFalse("data disk with fsSize present must NOT be isPoolMember", d.isPoolMember)
    }

    @Test fun parity_disk_is_not_pool_member() {
        // WHY: parity disks have no filesystem (fsSize=null by design) but are
        // NOT pool members — the flag must be false for parity type regardless
        // of fsSize. Only non-parity disks with fsSize=null are pool members.
        val data = arrayData(
            parities = listOf(parity(name = "parity1")),
        )
        val p = data.toArrayInfo().disks.first { it.type == DiskType.Parity }
        assertFalse("parity disk must NOT be isPoolMember (no fs is expected for parity)", p.isPoolMember)
    }

    // ── Builders ─────────────────────────────────────────────────────

    private fun arrayData(
        disks: List<GetArrayQuery.Disk> = emptyList(),
        parities: List<GetArrayQuery.Parity> = emptyList(),
        caches: List<GetArrayQuery.Cach> = emptyList(),
    ) = GetArrayQuery.Data(
        array = GetArrayQuery.Array(
            state = ArrayState.STARTED,
            capacity = GetArrayQuery.Capacity(
                kilobytes = GetArrayQuery.Kilobytes(total = "8000000000", used = "2000000000", free = "6000000000"),
            ),
            parities = parities,
            disks = disks,
            caches = caches,
            parityCheckStatus = null,
        ),
    )

    private fun disk(
        name: String,
        temp: Int? = 35,
        isSpinning: Boolean? = true,
        rotational: Boolean? = true,
        numErrors: Long? = null,
        warningC: Int? = null,
        criticalC: Int? = null,
        status: ArrayDiskStatus? = ArrayDiskStatus.DISK_OK,
    ) = GetArrayQuery.Disk(
        id = name,
        name = name,
        device = "sd${name.last()}",
        size = 4_000_000_000L,
        status = status,
        temp = temp,
        fsSize = 4_000_000_000L,
        fsFree = 2_000_000_000L,
        fsUsed = 2_000_000_000L,
        type = ArrayDiskType.DATA,
        isSpinning = isSpinning,
        rotational = rotational,
        numErrors = numErrors,
        warning = warningC,
        critical = criticalC,
    )

    private fun parity(
        name: String,
        temp: Int? = 35,
        isSpinning: Boolean? = true,
    ) = GetArrayQuery.Parity(
        id = name,
        name = name,
        device = "sd${name.last()}",
        size = 8_000_000_000L,
        status = ArrayDiskStatus.DISK_OK,
        temp = temp,
        type = ArrayDiskType.PARITY,
        isSpinning = isSpinning,
        rotational = true,
        numErrors = null,
        warning = null,
        critical = null,
    )

    private fun cache(
        name: String,
        fsSize: Long? = 4_000_000_000L,
        fsUsed: Long? = 1_000_000_000L,
        temp: Int? = 35,
        isSpinning: Boolean? = true,
    ) = GetArrayQuery.Cach(
        id = name,
        name = name,
        device = "sd${name.last()}",
        size = 4_000_000_000L,
        status = ArrayDiskStatus.DISK_OK,
        temp = temp,
        fsSize = fsSize,
        fsFree = if (fsSize != null && fsUsed != null) fsSize - fsUsed else null,
        fsUsed = fsUsed,
        type = ArrayDiskType.CACHE,
        isSpinning = isSpinning,
        rotational = false,
        numErrors = null,
        warning = null,
        critical = null,
    )
}
