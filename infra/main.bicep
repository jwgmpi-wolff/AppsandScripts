targetScope = 'resourceGroup'

param environmentName string
@minLength(1)
@maxLength(64)
param location string = resourceGroup().location

var namePrefix = toLower(take(environmentName, 14))

// Monitoring
module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    location: location
    namePrefix: namePrefix
  }
}

// Managed Identity
module identity 'modules/identity.bicep' = {
  name: 'identity'
  params: {
    location: location
    namePrefix: namePrefix
  }
}

// Service Bus
module serviceBus 'modules/service-bus.bicep' = {
  name: 'serviceBus'
  params: {
    location: location
    namePrefix: namePrefix
    identityPrincipalId: identity.outputs.identityPrincipalId
  }
}

// Storage
module storage 'modules/storage.bicep' = {
  name: 'storage'
  params: {
    location: location
    namePrefix: namePrefix
    identityPrincipalId: identity.outputs.identityPrincipalId
  }
}

// Registry
module registry 'modules/registry.bicep' = {
  name: 'registry'
  params: {
    location: location
    namePrefix: namePrefix
    identityPrincipalId: identity.outputs.identityPrincipalId
  }
}

// Container Apps Environment
module containerAppsEnv 'modules/container-apps-environment.bicep' = {
  name: 'containerAppsEnvironment'
  params: {
    location: location
    namePrefix: namePrefix
    logAnalyticsCustomerId: monitoring.outputs.logAnalyticsCustomerId
    logAnalyticsSharedKey: monitoring.outputs.logAnalyticsSharedKey
  }
}

// Container App
module containerApp 'modules/container-app.bicep' = {
  name: 'containerApp'
  params: {
    location: location
    namePrefix: namePrefix
    environmentId: containerAppsEnv.outputs.environmentId
    identityId: identity.outputs.identityId
    acrLoginServer: registry.outputs.acrLoginServer
    appInsightsConnectionString: monitoring.outputs.appInsightsConnectionString
    serviceBusNamespaceName: serviceBus.outputs.namespaceName
    queueName: serviceBus.outputs.queueNameOutput
    identityClientId: identity.outputs.identityClientId
    usePlaceholderImage: true
  }
}

// Outputs
output AZURE_CONTAINER_REGISTRY_ENDPOINT string = registry.outputs.acrLoginServer
output AZURE_CONTAINER_REGISTRY_NAME string = registry.outputs.acrName
output SERVICE_BUS_FQNS string = serviceBus.outputs.namespaceFqns
output SERVICE_BUS_NAMESPACE_NAME string = serviceBus.outputs.namespaceName
output QUEUE_NAME string = serviceBus.outputs.queueNameOutput
output BLOB_ACCOUNT_URL string = storage.outputs.blobEndpoint
output BLOB_ACCOUNT_NAME string = storage.outputs.storageAccountName
output BLOB_CONTAINER_NAME string = storage.outputs.containerNameOutput
@secure()
output APPLICATIONINSIGHTS_CONNECTION_STRING string = monitoring.outputs.appInsightsConnectionString
output CONTAINER_APP_NAME string = containerApp.outputs.containerAppName
output MANAGED_IDENTITY_CLIENT_ID string = identity.outputs.identityClientId
