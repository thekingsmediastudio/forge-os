package com.forge.os.service

import android.content.Context
import android.content.SharedPreferences
import com.forge.os.data.api.ConfigUpdateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

/**
 * Unit tests for ConfigService
 */
class ConfigServiceTest {
    
    private lateinit var configService: ConfigService
    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    
    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockSharedPreferences = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        
        `when`(mockContext.getSharedPreferences(anyString(), anyInt()))
            .thenReturn(mockSharedPreferences)
        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), any(Boolean::class.java))).thenReturn(mockEditor)
        `when`(mockEditor.commit()).thenReturn(true)
        
        // Setup default values
        `when`(mockSharedPreferences.getString("theme", "dark")).thenReturn("dark")
        `when`(mockSharedPreferences.getBoolean("sync_enabled", true)).thenReturn(true)
        `when`(mockSharedPreferences.getBoolean("clipboard_enabled", true)).thenReturn(true)
        `when`(mockSharedPreferences.getString("notification_filters", "[]")).thenReturn("[]")
        `when`(mockSharedPreferences.all).thenReturn(emptyMap<String, Any>())
        
        configService = ConfigService(mockContext)
    }
    
    @Test
    fun `getConfig returns default configuration`() = runBlocking {
        val config = configService.getConfig()
        
        assertEquals("dark", config.theme)
        assertTrue(config.syncEnabled)
        assertTrue(config.clipboardEnabled)
        assertTrue(config.notificationFilters.isEmpty())
        assertTrue(config.custom.isEmpty())
    }
    
    @Test
    fun `updateConfig with theme succeeds`() = runBlocking {
        val request = ConfigUpdateRequest(theme = "light")
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putString("theme", "light")
        verify(mockEditor).commit()
    }
    
    @Test
    fun `updateConfig with syncEnabled succeeds`() = runBlocking {
        val request = ConfigUpdateRequest(syncEnabled = false)
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putBoolean("sync_enabled", false)
        verify(mockEditor).commit()
    }
    
    @Test
    fun `updateConfig with clipboardEnabled succeeds`() = runBlocking {
        val request = ConfigUpdateRequest(clipboardEnabled = false)
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putBoolean("clipboard_enabled", false)
        verify(mockEditor).commit()
    }
    
    @Test
    fun `updateConfig with notification filters succeeds`() = runBlocking {
        val request = ConfigUpdateRequest(
            notificationFilters = listOf("com.example.app1", "com.example.app2")
        )
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putString(eq("notification_filters"), anyString())
        verify(mockEditor).commit()
    }
    
    @Test
    fun `updateConfig with multiple fields succeeds`() = runBlocking {
        val request = ConfigUpdateRequest(
            theme = "light",
            syncEnabled = false,
            clipboardEnabled = false
        )
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putString("theme", "light")
        verify(mockEditor).putBoolean("sync_enabled", false)
        verify(mockEditor).putBoolean("clipboard_enabled", false)
        verify(mockEditor).commit()
    }
    
    @Test
    fun `updateConfig with no fields returns success without changes`() = runBlocking {
        val request = ConfigUpdateRequest()
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor, never()).commit()
    }
    
    @Test
    fun `updateConfig with custom fields succeeds`() = runBlocking {
        val customMap = mapOf(
            "custom_key" to buildJsonObject { put("value", "test") }
        )
        val request = ConfigUpdateRequest(custom = customMap)
        
        val result = configService.updateConfig(request)
        
        assertTrue(result)
        verify(mockEditor).putString(eq("custom_custom_key"), anyString())
        verify(mockEditor).commit()
    }
    
    @Test
    fun `isFeatureEnabled returns correct status for sync`() = runBlocking {
        `when`(mockSharedPreferences.getBoolean("sync_enabled", true)).thenReturn(true)
        
        val enabled = configService.isFeatureEnabled("sync")
        
        assertTrue(enabled)
    }
    
    @Test
    fun `isFeatureEnabled returns correct status for clipboard`() = runBlocking {
        `when`(mockSharedPreferences.getBoolean("clipboard_enabled", true)).thenReturn(false)
        
        val enabled = configService.isFeatureEnabled("clipboard")
        
        assertFalse(enabled)
    }
    
    @Test
    fun `isFeatureEnabled returns false for unknown feature`() = runBlocking {
        val enabled = configService.isFeatureEnabled("unknown_feature")
        
        assertFalse(enabled)
    }
    
    @Test
    fun `resetToDefaults clears all preferences`() = runBlocking {
        `when`(mockEditor.clear()).thenReturn(mockEditor)
        
        val result = configService.resetToDefaults()
        
        assertTrue(result)
        verify(mockEditor).clear()
        verify(mockEditor).commit()
    }
}
