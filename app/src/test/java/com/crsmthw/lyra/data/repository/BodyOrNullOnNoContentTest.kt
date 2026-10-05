package com.crsmthw.lyra.data.repository

import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The `Response<T>` unwrap behind `me/player`, its queue and its devices (2026-10-04): Retrofit
 * 3.0.0 threw on a 204 for a `T?` suspend return, so the poll's "no active device" arm was dead.
 */
class BodyOrNullOnNoContentTest {

    @Test
    fun `a 204 is null`() {
        assertNull(Response.success<String>(204, null).bodyOrNullOnNoContent())
    }

    @Test
    fun `a 200 is its body`() {
        assertEquals("state", Response.success(200, "state").bodyOrNullOnNoContent())
    }

    @Test
    fun `a non-2xx throws the HttpException safeCall maps to its HTTP text`() {
        val e429 = assertFailsWith<HttpException> {
            Response.error<String>(429, "".toResponseBody(null)).bodyOrNullOnNoContent()
        }
        assertEquals(429, e429.code())
        val e404 = assertFailsWith<HttpException> {
            Response.error<String>(404, "{}".toResponseBody(null)).bodyOrNullOnNoContent()
        }
        assertEquals(404, e404.code())
    }
}
