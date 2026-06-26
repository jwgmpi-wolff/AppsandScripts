targetScope = 'subscription'

@description('Name of the environment used to generate globally unique resource names.')
param environmentName string

@description('Primary location for all resources.')
param location string

@description('azd service name used for deployment discovery.')
param serviceName string = 'api'

var rgName = 'rg-${environmentName}'
var suffix = toLower(uniqueString(subscription().id, environmentName, 'linux-v1'))
var appServicePlanName = 'plan-${environmentName}-${take(suffix, 6)}'
var appInsightsName = 'appi-${environmentName}-${take(suffix, 6)}'
var functionAppName = 'func-${environmentName}-${take(suffix, 6)}'
var storageName = 'st${take(replace(toLower(environmentName), '-', ''), 11)}${take(suffix, 8)}'

resource rg 'Microsoft.Resources/resourceGroups@2022-09-01' = {
  name: rgName
  location: location
}

module appInfra 'resources.bicep' = {
  name: 'app-infra-${environmentName}'
  scope: resourceGroup(rg.name)
  params: {
    location: location
    environmentName: environmentName
    serviceName: serviceName
    appServicePlanName: appServicePlanName
    appInsightsName: appInsightsName
    functionAppName: functionAppName
    storageName: storageName
  }
}

output AZURE_LOCATION string = location
output AZURE_RESOURCE_GROUP string = rg.name
output AZURE_FUNCTION_APP_NAME string = appInfra.outputs.functionAppName
output SERVICE_API_NAME string = appInfra.outputs.functionAppName
output SERVICE_API_HOSTNAME string = appInfra.outputs.functionAppHostname
output SERVICE_API_URI string = 'https://${appInfra.outputs.functionAppHostname}'
output SERVICE_API_URL string = 'https://${appInfra.outputs.functionAppHostname}'
output SERVICE_API_RESOURCE_ID string = appInfra.outputs.functionAppResourceId
