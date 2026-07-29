targetScope = 'resourceGroup'

@description('Location for all resources')
param location string = resourceGroup().location

@description('App name prefix, globally unique constraints apply where required')
param appName string

@description('Backend container image or build deployment package setting.')
param backendRuntime string = 'DOTNETCORE|8.0'

@description('Key Vault secret name that stores market data provider API key')
param marketProviderApiKeySecretName string = 'market-provider-api-key'

@description('Microsoft Entra tenant ID')
param tenantId string

@description('Backend API app registration client ID')
param backendApiClientId string

@description('Backend API audience value, e.g. api://<backend-client-id>')
param backendApiAudience string

var appServicePlanName = '${appName}-asp'
var appServiceName = '${appName}-api'
var keyVaultName = toLower(replace('${appName}-kv', '-', ''))
var appConfigName = '${appName}-appcfg'
var signalRName = '${appName}-signalr'
var storageName = toLower(replace('${appName}stg', '-', ''))
var logAnalyticsName = '${appName}-law'
var appInsightsName = '${appName}-appi'

resource appServicePlan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: appServicePlanName
  location: location
  sku: {
    name: 'P1v3'
    tier: 'PremiumV3'
    size: 'P1v3'
    capacity: 1
  }
  kind: 'linux'
  properties: {
    reserved: true
  }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageName
  location: location
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    minimumTlsVersion: 'TLS1_2'
    allowBlobPublicAccess: false
    supportsHttpsTrafficOnly: true
  }
}

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: logAnalyticsName
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalytics.id
  }
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-02-01' = {
  name: keyVaultName
  location: location
  properties: {
    tenantId: tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enabledForDeployment: false
    enabledForTemplateDeployment: false
    enableRbacAuthorization: true
    publicNetworkAccess: 'Enabled'
    softDeleteRetentionInDays: 90
  }
}

resource appConfig 'Microsoft.AppConfiguration/configurationStores@2024-05-01' = {
  name: appConfigName
  location: location
  sku: {
    name: 'standard'
  }
  properties: {
    publicNetworkAccess: 'Enabled'
    disableLocalAuth: true
  }
}

resource signalR 'Microsoft.SignalRService/SignalR@2023-02-01' = {
  name: signalRName
  location: location
  sku: {
    name: 'Standard_S1'
    capacity: 1
  }
  kind: 'SignalR'
  properties: {
    tls: {
      clientCertEnabled: false
    }
    publicNetworkAccess: 'Enabled'
    features: [
      {
        flag: 'ServiceMode'
        value: 'Default'
      }
    ]
  }
}

resource webApp 'Microsoft.Web/sites@2023-12-01' = {
  name: appServiceName
  location: location
  kind: 'app,linux'
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: appServicePlan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: backendRuntime
      minTlsVersion: '1.2'
      alwaysOn: true
      appSettings: [
        {
          name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
          value: appInsights.properties.ConnectionString
        }
        {
          name: 'KeyVault__Uri'
          value: keyVault.properties.vaultUri
        }
        {
          name: 'AzureAd__TenantId'
          value: tenantId
        }
        {
          name: 'AzureAd__ClientId'
          value: backendApiClientId
        }
        {
          name: 'AzureAd__Audience'
          value: backendApiAudience
        }
        {
          name: 'MarketDataProvider__ApiKeySecretName'
          value: marketProviderApiKeySecretName
        }
        {
          name: 'MarketDataProvider__ProviderName'
          value: 'REPLACE_WITH_PROVIDER_NAME'
        }
        {
          name: 'MarketDataProvider__BaseUrl'
          value: 'REPLACE_WITH_PROVIDER_BASE_URL'
        }
        {
          name: 'SignalR__ConnectionString'
          value: signalR.listKeys().primaryConnectionString
        }
      ]
    }
  }
}

resource appConfigRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(webApp.id, appConfig.id, 'app-config-data-reader')
  scope: appConfig
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '516239f1-63e1-4d78-a4de-a74fb236a071')
    principalId: webApp.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

resource keyVaultRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(webApp.id, keyVault.id, 'keyvault-secrets-user')
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
    principalId: webApp.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

output webAppName string = webApp.name
output keyVaultUri string = keyVault.properties.vaultUri
output signalRName string = signalR.name
output appConfigEndpoint string = appConfig.properties.endpoint
