package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class CaptureSpaceBudgetTest {
    private val mib=1024L*1024
    @Test fun videoAdmissionReservesSourceAndTwoOutputCopiesAndTheSafetyMargin() {
        assertEquals(192*mib,CaptureSpaceBudget.videoBudget(1024*mib,0))
        assertEquals(160*mib,CaptureSpaceBudget.videoBudget(1024*mib,128*mib))
        assertTrue(CaptureSpaceBudget.videoBudget(300*mib,0)<128*mib)
    }
    @Test fun pendingExportsAndSafetyMarginCanBlockAnotherPhotoWithoutDiscardingAnything() {
        assertTrue(CaptureSpaceBudget.canAcceptPhoto(1024*mib,128*mib))
        assertFalse(CaptureSpaceBudget.canAcceptPhoto(300*mib,128*mib))
    }
}
