package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchLoginNameTest {
    @Test
    fun acceptsDefaultAndValidLinuxNames() {
        assertNull(ArchLoginName.validate("dev", "root:x:0:0::/root:/bin/bash\ndev:x:1000:1000::/home/dev:/usr/bin/fish\n"))
        assertNull(ArchLoginName.validate("miguel_regueiro", "root:x:0:0::/root:/bin/bash\ndev:x:1000:1000::/home/dev:/usr/bin/fish\n"))
    }

    @Test
    fun rejectsInvalidReservedAndExistingNames() {
        assertEquals(true, ArchLoginName.validate("Root", "") != null)
        assertEquals(true, ArchLoginName.validate("root", "") != null)
        assertEquals(true, ArchLoginName.validate("bad/name", "") != null)
        assertEquals(true, ArchLoginName.validate("already", "root:x:0:0::/root:/bin/bash\nalready:x:1001:1001::/home/already:/bin/bash\n") != null)
        assertEquals(true, ArchLoginName.validate("wheel", "", groupText = "wheel:x:10:\n") != null)
    }
}
