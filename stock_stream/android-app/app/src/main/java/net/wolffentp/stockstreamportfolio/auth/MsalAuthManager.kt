package net.wolffentp.stockstreamportfolio.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.ICurrentAccountResult
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.wolffentp.stockstreamportfolio.BuildConfig
import net.wolffentp.stockstreamportfolio.R
import kotlin.coroutines.resume

class MsalAuthManager(private val context: Context) {
    private val logTag = "MsalAuthManager"
    private var pca: IPublicClientApplication? = null
    private var currentAccount: IAccount? = null

    data class SignInResult(
        val accessToken: String,
        val accountId: String
    )

    suspend fun initialize(): Result<Unit> = suspendCancellableCoroutine { continuation ->
        Log.d(logTag, "Initializing MSAL public client application")
        PublicClientApplication.create(
            context,
            R.raw.auth_config_single_account,
            object : IPublicClientApplication.ApplicationCreatedListener {
                override fun onCreated(application: IPublicClientApplication) {
                    pca = application
                    Log.d(logTag, "MSAL initialized successfully")
                    continuation.resume(Result.success(Unit))
                }

                override fun onError(exception: MsalException) {
                    Log.e(logTag, "MSAL initialization failed", exception)
                    continuation.resume(Result.failure(exception))
                }
            }
        )
    }

    suspend fun signIn(activity: Activity): Result<SignInResult> {
        Log.d(logTag, "Starting interactive sign-in")
        if (hasPlaceholderAuthConfiguration()) {
            return Result.failure(
                IllegalStateException(
                    "MSAL is not configured. Replace STOCKSTREAM_ANDROID_CLIENT_ID, STOCKSTREAM_TENANT_ID, STOCKSTREAM_BACKEND_SCOPE, auth_config_single_account.json, and AndroidManifest redirect host before signing in."
                )
            )
        }

        val app = pca ?: return Result.failure(IllegalStateException("MSAL not initialized"))
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    currentAccount = authenticationResult.account
                    Log.d(logTag, "Interactive sign-in succeeded for accountId=${authenticationResult.account?.id ?: authenticationResult.account?.username ?: "unknown"}")
                    continuation.resume(
                        Result.success(
                            SignInResult(
                                accessToken = authenticationResult.accessToken,
                                accountId = authenticationResult.account?.id
                                    ?: authenticationResult.account?.username
                                    ?: "unknown"
                            )
                        )
                    )
                }

                override fun onError(exception: MsalException) {
                    Log.e(logTag, "Interactive sign-in failed: ${exception.javaClass.name}: ${exception.message}", exception)
                    continuation.resume(Result.failure(exception))
                }

                override fun onCancel() {
                    Log.w(logTag, "Interactive sign-in cancelled")
                    continuation.resume(Result.failure(IllegalStateException("Sign in cancelled")))
                }
            }

            when (app) {
                is ISingleAccountPublicClientApplication -> {
                    val current = currentAccount
                    if (current == null) {
                        app.signIn(activity, null, arrayOf(BuildConfig.BACKEND_SCOPE), callback)
                    } else {
                        app.signInAgain(activity, arrayOf(BuildConfig.BACKEND_SCOPE), Prompt.SELECT_ACCOUNT, callback)
                    }
                }
                else -> {
                    app.acquireToken(activity, arrayOf(BuildConfig.BACKEND_SCOPE), callback)
                }
            }
        }
    }

    fun clearCurrentAccount() {
        currentAccount = null
    }

    suspend fun signOut(): Result<Unit> {
        val app = pca ?: return Result.success(Unit)

        return when (app) {
            is ISingleAccountPublicClientApplication -> suspendCancellableCoroutine { continuation ->
                app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                    override fun onSignOut() {
                        currentAccount = null
                        continuation.resume(Result.success(Unit))
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(logTag, "Sign-out failed", exception)
                        continuation.resume(Result.failure(exception))
                    }
                })
            }
            else -> {
                clearCurrentAccount()
                Result.success(Unit)
            }
        }
    }

    suspend fun getCurrentAccount(): IAccount? {
        currentAccount?.let { return it }

        val app = pca
        if (app is ISingleAccountPublicClientApplication) {
            return suspendCancellableCoroutine { continuation ->
                app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                    override fun onAccountLoaded(account: IAccount?) {
                        currentAccount = account
                        continuation.resume(account)
                    }

                    override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                        this@MsalAuthManager.currentAccount = currentAccount
                        continuation.resume(currentAccount)
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(logTag, "Failed to read current account", exception)
                        continuation.resume(null)
                    }
                })
            }
        }

        if (app is IMultipleAccountPublicClientApplication) {
            return suspendCancellableCoroutine { continuation ->
                app.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
                    override fun onTaskCompleted(accounts: MutableList<IAccount>?) {
                        currentAccount = accounts?.firstOrNull()
                        continuation.resume(currentAccount)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resume(null)
                    }
                })
            }
        }

        return null
    }

    suspend fun acquireTokenSilent(): String? {
        val app = pca ?: return null
        val account = getCurrentAccount() ?: return null

        Log.d(logTag, "Attempting silent token acquisition for account=${account.id}")

        return suspendCancellableCoroutine { continuation ->
            val parameters = AcquireTokenSilentParameters.Builder()
                .forAccount(account)
                .fromAuthority("https://login.microsoftonline.com/${BuildConfig.TENANT_ID}")
                .withScopes(listOf(BuildConfig.BACKEND_SCOPE))
                .withCallback(object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resume(null)
                    }
                })
                .build()

            app.acquireTokenSilentAsync(parameters)
        }
    }

    private fun hasPlaceholderAuthConfiguration(): Boolean {
        return BuildConfig.ANDROID_CLIENT_ID.contains("REPLACE", ignoreCase = true)
            || BuildConfig.TENANT_ID.contains("REPLACE", ignoreCase = true)
            || BuildConfig.BACKEND_SCOPE.contains("REPLACE", ignoreCase = true)
    }
}
