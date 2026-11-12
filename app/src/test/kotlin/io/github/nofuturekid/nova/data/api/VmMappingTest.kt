package io.github.nofuturekid.nova.data.api

import io.github.nofuturekid.nova.data.model.VmState
import io.github.nofuturekid.nova.graphql.GetVmsQuery
import io.github.nofuturekid.nova.graphql.type.VmState as GVmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmMappingTest {
    @Test fun vm_specs_the_api_does_not_expose_map_to_unknown_not_zero() {
        // WHY (#243): GetVms returns only id, name and state. The mapper used to
        // fill vcpus = 0 and memGb = 0, which the UI printed as "0 vCPU · 0 GB".
        // Unknown must stay null so the UI can hide it; a 0 here would be shown.
        val data = GetVmsQuery.Data(
            vms = GetVmsQuery.Vms(
                domains = listOf(GetVmsQuery.Domain(id = "v1", name = "Windows 11", state = GVmState.RUNNING)),
            ),
        )
        val vm = data.toVms().single()
        assertEquals("Windows 11", vm.name)
        assertEquals(VmState.Running, vm.state)
        assertNull(vm.vcpus)
        assertNull(vm.memGb)
        assertNull(vm.gpu)
    }
}
