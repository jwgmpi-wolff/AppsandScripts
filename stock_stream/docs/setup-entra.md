# Microsoft Entra Setup

## App Registrations

Create two app registrations in the same tenant:

1. Backend API app registration
- Expose API scope: `access_as_user`
- Application ID URI: `api://<backend-api-client-id>`
- Add app roles: `Admin`, `User`
- Configure optional/group claims as needed

2. Android native app registration
- Platform: Mobile and desktop applications
- Redirect URI format for MSAL Android
- Add delegated permission to backend scope: `api://<backend-api-client-id>/access_as_user`

## Guest User Access

- Invite guest users to tenant.
- Assign guest users to an Entra group mapped to `User` role, or assign app role directly.
- Assign admins to `Admin` role.

## Token Validation Requirements

Backend must validate:
- issuer
- audience
- signature
- expiry
- tenant alignment

## Required Placeholder Replacements

- Android `auth_config_single_account.json`
- Android local gradle properties (`STOCKSTREAM_TENANT_ID`, `STOCKSTREAM_ANDROID_CLIENT_ID`, `STOCKSTREAM_BACKEND_SCOPE`)
- Backend `appsettings.json` AzureAd values
