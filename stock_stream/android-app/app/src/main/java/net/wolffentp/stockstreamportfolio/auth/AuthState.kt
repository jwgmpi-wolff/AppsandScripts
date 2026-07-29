package net.wolffentp.stockstreamportfolio.auth

sealed interface AuthState {
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data class SignedIn(val accountId: String) : AuthState
    data class Error(val message: String) : AuthState
}
