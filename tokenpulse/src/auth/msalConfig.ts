import { PublicClientApplication, type Configuration } from '@azure/msal-browser';

const clientId = import.meta.env.VITE_AZURE_CLIENT_ID;
const tenantId = import.meta.env.VITE_AZURE_TENANT_ID;

if (!clientId) {
	// Keep this explicit so local setup issues fail fast and clearly.
	throw new Error('Missing VITE_AZURE_CLIENT_ID in environment.');
}

const authorityTenant = tenantId && tenantId.trim() ? tenantId.trim() : 'organizations';

const msalConfig: Configuration = {
	auth: {
		clientId,
		authority: `https://login.microsoftonline.com/${authorityTenant}`,
		redirectUri: window.location.origin,
		postLogoutRedirectUri: window.location.origin,
	},
	cache: {
		cacheLocation: 'sessionStorage',
	},
};

export const msalInstance = new PublicClientApplication(msalConfig);

export const armLoginRequest = {
	scopes: ['https://management.azure.com/user_impersonation'],
};
