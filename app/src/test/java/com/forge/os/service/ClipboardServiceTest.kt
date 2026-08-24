package com.forge.os.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.forge.os.data.api.ClipboardUpdateRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*

/**
 * Unit tests for ClipboardService
 */
class ClipboardServiceTest {
    
    private lateinit var clipboardService: ClipboardService
    private lateinit var mockContext: Context
    private lateinit var mockClipboardManager: ClipboardManager
    
    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockClipboardManager = mock(ClipboardManager::class.java)
        
        `when`(mockContext.getSystemService(Context.CLIPBOARD_SERVICE))
            .thenReturn(mockClipboardManager)
        
        clipboardService = ClipboardService(mockContext)
    }
    
    @Test
    fun `updateClipboard with text content succeeds`() = runBlocking {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = "Test content"
        )
        
        val result = clipboardService.updateClipboard(request)
        
        assertTrue(result)
        verify(mockClipboardManager).setPrimaryClip(any(ClipData::class.java))
    }
    
    @Test
    fun `updateClipboard with blank text content fails`() = runBlocking {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = ""
        )
        
        val result = clipboardService.updateClipboard(request)
        
        assertFalse(result)
        verify(mockClipboardManager, never()).setPrimaryClip(any(ClipData::class.java))
    }
    
    @Test
    fun `updateClipboard with null text content fails`() = runBlocking {
        val request = ClipboardUpdateRequest(
            type = "text",
            content = null
        )
        
        val result = clipboardService.updateClipboard(request)
        
        assertFalse(result)
        verify(mockClipboardManager, never()).setPrimaryClip(any(ClipData::class.java))
    }
    
    @Test
    fun `updateClipboard with file name succeeds`() = runBlocking {
        val request = ClipboardUpdateRequest(
            type = "file",
            fileName = "document.pdf"
        )
        
        val result = clipboardService.updateClipboard(request)
        
        assertTrue(result)
        verify(mockClipboardManager).setPrimaryClip(any(ClipData::class.java))
    }
    
    @Test
    fun `updateClipboard with unsupported type fails`() = runBlocking {
        val request = ClipboardUpdateRequest(
            type = "unsupported",
            content = "test"
        )
        
        val result = clipboardService.updateClipboard(request)
        
        assertFalse(result)
    }
}
