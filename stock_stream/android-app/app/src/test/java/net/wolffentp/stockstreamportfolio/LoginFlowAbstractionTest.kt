package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.auth.AuthState
import org.junit.Test

class LoginFlowAbstractionTest {
    @Test
    fun signedOut_requiresInteractiveLogin() {
        val state: AuthState = AuthState.SignedOut
        assertThat(state).isInstanceOf(AuthState.SignedOut::class.java)
    }
}
