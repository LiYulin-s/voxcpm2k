package top.yurin.voxcpm2

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxNativeIntegrationTest {
    @Test
    fun sharedRuntimeLoadsAndReportsNativeErrors(): Unit = runBlocking {
        assertFailsWith<VoxCPM2Exception> {
            VoxCPM2.open(VoxCPM2Config("/definitely-missing/voxcpm2k-model"))
        }
        Unit
    }
}
