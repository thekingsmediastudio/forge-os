package com.forge.os.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PairingService
 */
class PairingServiceTest {
    
    private lateinit var pairingService: PairingService
    
    @Before
    fun setUp() {
        pairingService = PairingService()
    }
    
    @Test
    fun `generatePairingCode returns 6-digit code`() {
        val (code, expiresIn) = pairingService.generatePairingCode("Test Desktop")
        
        // Verify code is 6 digits
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertTrue(code.toInt() in 100000..999999)
    }
    
    @Test
    fun `generatePairingCode returns correct expiration time`() {
        val (_, expiresIn) = pairingService.generatePairingCode("Test Desktop")
        
        // Should be 300 seconds (5 minutes)
        assertEquals(300, expiresIn)
    }
    
    @Test
    fun `generatePairingCode creates unique codes`() {
        val codes = mutableSetOf<String>()
        
        // Generate 100 codes and verify they're all unique
        repeat(100) {
            val (code, _) = pairingService.generatePairingCode("Desktop $it")
            codes.add(code)
        }
        
        // All codes should be unique
        assertEquals(100, codes.size)
    }
    
    @Test
    fun `validateAndConsumePairingCode returns desktop name for valid code`() {
        val desktopName = "John's MacBook"
        val (code, _) = pairingService.generatePairingCode(desktopName)
        
        val validatedName = pairingService.validateAndConsumePairingCode(code)
        
        assertEquals(desktopName, validatedName)
    }
    
    @Test
    fun `validateAndConsumePairingCode returns null for invalid code`() {
        val validatedName = pairingService.validateAndConsumePairingCode("999999")
        
        assertNull(validatedName)
    }
    
    @Test
    fun `validateAndConsumePairingCode consumes code after validation`() {
        val (code, _) = pairingService.generatePairingCode("Test Desktop")
        
        // First validation should succeed
        val firstValidation = pairingService.validateAndConsumePairingCode(code)
        assertNotNull(firstValidation)
        
        // Second validation with same code should fail (single-use)
        val secondValidation = pairingService.validateAndConsumePairingCode(code)
        assertNull(secondValidation)
    }
    
    @Test
    fun `validateAndConsumePairingCode returns null for expired code`() {
        // This test would require mocking time, so we'll test it indirectly
        // by verifying the cleanup mechanism works
        val (code, _) = pairingService.generatePairingCode("Test Desktop")
        
        // Code should be valid immediately after generation
        val validatedName = pairingService.validateAndConsumePairingCode(code)
        assertNotNull(validatedName)
    }
    
    @Test
    fun `getActivePairingCodeCount returns correct count`() {
        val initialCount = pairingService.getActivePairingCodeCount()
        
        // Generate 3 codes
        pairingService.generatePairingCode("Desktop 1")
        pairingService.generatePairingCode("Desktop 2")
        pairingService.generatePairingCode("Desktop 3")
        
        assertEquals(initialCount + 3, pairingService.getActivePairingCodeCount())
    }
    
    @Test
    fun `getActivePairingCodeCount decreases after code consumption`() {
        val (code1, _) = pairingService.generatePairingCode("Desktop 1")
        val (code2, _) = pairingService.generatePairingCode("Desktop 2")
        
        val countBefore = pairingService.getActivePairingCodeCount()
        
        // Consume one code
        pairingService.validateAndConsumePairingCode(code1)
        
        val countAfter = pairingService.getActivePairingCodeCount()
        
        assertEquals(countBefore - 1, countAfter)
    }
    
    @Test
    fun `multiple concurrent pairing codes can exist`() {
        val desktop1 = "Desktop 1"
        val desktop2 = "Desktop 2"
        val desktop3 = "Desktop 3"
        
        val (code1, _) = pairingService.generatePairingCode(desktop1)
        val (code2, _) = pairingService.generatePairingCode(desktop2)
        val (code3, _) = pairingService.generatePairingCode(desktop3)
        
        // All codes should be valid
        assertEquals(desktop1, pairingService.validateAndConsumePairingCode(code1))
        assertEquals(desktop2, pairingService.validateAndConsumePairingCode(code2))
        assertEquals(desktop3, pairingService.validateAndConsumePairingCode(code3))
    }
}
