param location string
param namePrefix string

resource userAssignedIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${namePrefix}-umi'
  location: location
}

output identityId string = userAssignedIdentity.id
output identityPrincipalId string = userAssignedIdentity.properties.principalId
output identityClientId string = userAssignedIdentity.properties.clientId
