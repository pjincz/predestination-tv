package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class CastTitleTest {
    @Test fun readsDublinCoreTitleWithAnyPrefixAndDecodesEntities() {
        assertEquals("偏爱 & Live", CastTitle.parse("""
            <DIDL-Lite xmlns:d="http://purl.org/dc/elements/1.1/">
              <item><d:title>  偏爱 &amp; Live </d:title></item>
            </DIDL-Lite>
        """))
        assertEquals("下一首", CastTitle.parse("""
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item><dc:title><![CDATA[下一首]]></dc:title></item>
            </DIDL-Lite>
        """))
    }

    @Test fun absentInvalidAndUnsafeTitlesFallBack() {
        assertNull(CastTitle.parse(""))
        assertNull(CastTitle.parse("<DIDL-Lite><item/></DIDL-Lite>"))
        assertNull(CastTitle.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><x/>"))
        assertNull(CastTitle.parse("<DIDL-Lite><title>not Dublin Core</title></DIDL-Lite>"))
    }
}
