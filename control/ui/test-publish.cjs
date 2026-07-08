const { ApiClient } = require('./e2e/helpers/api');

async function main() {
  const api = new ApiClient();
  await api.login('admin@e2e-test.local', 'E2eTest@123!');
  
  // List assets
  const assets = await api.request('GET', '/admin/instruction-assets');
  console.log('Assets:', JSON.stringify(assets.map(a => ({ id: a.id, name: a.name, status: a.status })), null, 2));
  
  if (assets.length > 0) {
    const asset = assets[0];
    
    // Check for draft
    try {
      const draft = await api.request('GET', `/admin/instruction-assets/${asset.id}/draft-version`);
      console.log('Draft exists:', draft ? 'yes' : 'no');
    } catch (e) {
      console.log('Draft check error:', e.message);
    }
    
    // Try to publish
    try {
      const published = await api.request('POST', `/admin/instruction-assets/${asset.id}/publish`);
      console.log('Publish OK:', published.status);
    } catch (e) {
      console.log('Publish error:', e.message);
    }
  }
}

main().catch(console.error);