@description('Azure region for deployment')
param location string = resourceGroup().location

@description('App Service name (must be globally unique)')
param appName string

@description('App Service plan name')
param appServicePlanName string = '${appName}-plan'

@description('SKU for App Service plan')
@allowed([
  'B1'
  'S1'
  'P1v3'
])
param appServiceSkuName string = 'S1'

@description('Optional upstream base URL for tenant data API')
param upstreamBaseUrl string = ''

@description('Optional upstream bearer token for tenant API')
@secure()
param upstreamBearerToken string = ''

resource appServicePlan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: appServicePlanName
  location: location
  sku: {
    name: appServiceSkuName
    tier: appServiceSkuName == 'P1v3' ? 'PremiumV3' : (appServiceSkuName == 'S1' ? 'Standard' : 'Basic')
    capacity: 1
  }
  kind: 'linux'
  properties: {
    reserved: true
  }
}

resource webApp 'Microsoft.Web/sites@2023-12-01' = {
  name: appName
  location: location
  kind: 'app,linux'
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: appServicePlan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: 'NODE|22-lts'
      appCommandLine: 'npm start'
      minTlsVersion: '1.2'
      alwaysOn: appServiceSkuName != 'B1'
      ftpsState: 'Disabled'
      healthCheckPath: '/healthz'
      appSettings: [
        {
          name: 'NODE_ENV'
          value: 'production'
        }
        {
          name: 'SCM_DO_BUILD_DURING_DEPLOYMENT'
          value: 'true'
        }
        {
          name: 'ENABLE_ORYX_BUILD'
          value: 'true'
        }
        {
          name: 'TOKENPULSE_UPSTREAM_BASE_URL'
          value: upstreamBaseUrl
        }
        {
          name: 'TOKENPULSE_UPSTREAM_BEARER_TOKEN'
          value: upstreamBearerToken
        }
      ]
    }
  }
}

output webAppName string = webApp.name
output webAppUrl string = 'https://${webApp.properties.defaultHostName}'
