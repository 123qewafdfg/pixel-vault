package com.trustmark.share

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class BiliPayloadTest {
    @get:Rule val temp=TemporaryFolder()
    private val mib=1024L*1024
    private fun item(actual:Long,original:Long):LocalMedia = LocalMedia(temp.newFile().apply {
        RandomAccessFile(this,"rw").use{it.setLength(actual)}
    },"compressed.webp","image/webp",sourceSize=original)

    @Test fun compressedTwoMibIsNotReportedAsItsSixtyMibOriginal() {
        val items=listOf(item(2*mib,60*mib))
        assertEquals(2*mib,BiliPayload.size(items))
        assertTrue(BiliPayload.shouldSuggest(true,items,false))
    }
    @Test fun multipleProcessedFilesUseTheirCombinedActualSize() {
        val items=listOf(item(mib,20*mib),item(2*mib,30*mib))
        assertEquals(3*mib,BiliPayload.size(items))
        assertTrue(BiliPayload.shouldSuggest(true,items,false))
    }
    @Test fun oneHundredMibIsAllowedWithoutCountingCarrierOrOldSourceSize() {
        val item=item(100*mib,180*mib)
        assertTrue(BiliPayload.shouldSuggest(true,listOf(item),false))
        RandomAccessFile(item.file,"rw").use{it.setLength(100*mib+1)}
        assertFalse(BiliPayload.shouldSuggest(true,listOf(item),false))
    }
    @Test fun loggedOutEmptyOrUncertainFlowsDoNotSuggestUpload() {
        val items=listOf(item(mib,17*mib))
        assertFalse(BiliPayload.shouldSuggest(false,items,false))
        assertFalse(BiliPayload.shouldSuggest(true,emptyList(),false))
        assertFalse(BiliPayload.shouldSuggest(true,items,true))
    }
}
