param location string
param namePrefix string
param logAnalyticsCustomerId string
@secure()
param logAnalyticsSharedKey string

resource containerAppEnvironment 'Microsoft.App/managedEnvironments@2024-10-02-preview' = {
  name: '${namePrefix}-cae'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalyticsCustomerId
        sharedKey: logAnalyticsSharedKey
      }
    }
    zoneRedundant: false
  }
}

output environmentId string = containerAppEnvironment.id
output environmentName string = containerAppEnvironment.name
