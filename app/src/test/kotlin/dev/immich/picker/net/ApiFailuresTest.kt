package dev.immich.picker.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFailuresTest {

    private fun failure(code: Int, message: String? = null) =
        ApiFailures.forStatus("GET", "/users/me", code, message)

    // -------------------------------------------------------------- status mapping

    @Test
    fun `401 is Unauthorized so the app asks for a new sign in`() {
        assertTrue(failure(401) is ApiException.Unauthorized)
    }

    @Test
    fun `403 is Forbidden and never Unauthorized`() {
        // The whole point of the split: 403 must not trigger a re-login prompt.
        val error = failure(403)
        assertTrue(error is ApiException.Forbidden)
        assertTrue(error !is ApiException.Unauthorized)
    }

    @Test
    fun `other statuses are plain HTTP errors`() {
        val error = failure(500, "boom")
        assertTrue(error is ApiException.Http)
        assertEquals(500, (error as ApiException.Http).status)
    }

    @Test
    fun `401 without a server message still explains itself`() {
        assertEquals(
            "Not authorised (HTTP 401)",
            (failure(401) as ApiException.Unauthorized).message,
        )
    }

    // -------------------------------------------------------------- permission name

    @Test
    fun `403 names the missing permission Immich reports`() {
        val error = failure(403, "Missing required permission: asset.read")
        assertEquals("asset.read", (error as ApiException.Forbidden).permission)
    }

    @Test
    fun `403 message names the permission so the fix is obvious`() {
        val error = failure(403, "Missing required permission: asset.read")
        assertTrue(error.message!!.contains("asset.read"))
    }

    @Test
    fun `permission extraction handles a camelCase resource`() {
        assertEquals(
            "adminUser.read",
            ApiFailures.permissionFrom("Missing required permission: adminUser.read"),
        )
    }

    @Test
    fun `permission extraction ignores unrelated messages`() {
        assertNull(ApiFailures.permissionFrom("Forbidden"))
        assertNull(ApiFailures.permissionFrom(null))
    }

    @Test
    fun `permission extraction ignores a bare word that is not a permission`() {
        // Mentions "permission" but names nothing dotted, so nothing should be guessed.
        assertNull(ApiFailures.permissionFrom("You do not have permission to do that"))
    }
}
