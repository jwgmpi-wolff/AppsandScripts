# GitHub Deployment Guide

## 🚀 Push to GitHub Repository

### Step 1: Create GitHub Repository

1. Go to [github.com/new](https://github.com/new)
2. **Repository name**: `trafficcams`
3. **Description**: "Interactive Traffic Camera Dashboard - 596+ WSDOT cameras with location classification"
4. **Public** (recommended for portfolio/open source)
5. ⚠️ **DO NOT initialize** with README, .gitignore, or license
6. Click "Create repository"

### Step 2: Connect Local Repository to GitHub

```bash
cd C:\.git\trafficcams

# Add GitHub remote (replace YOUR-USERNAME)
git remote add origin https://github.com/YOUR-USERNAME/trafficcams.git

# Verify remote is added
git remote -v
# Should output:
# origin  https://github.com/YOUR-USERNAME/trafficcams.git (fetch)
# origin  https://github.com/YOUR-USERNAME/trafficcams.git (push)
```

### Step 3: Push to GitHub

```bash
# Rename branch to main (if needed)
git branch -M main

# Push all commits to GitHub
git push -u origin main

# Verify push successful
# You should see:
# Branch 'main' set up to track remote branch 'main' from 'origin'.
```

---

## 🌐 Deploy to Production Hosting

### Option 1: Azure App Service (Recommended)

**Automatic GitHub Deployment:**

1. **Create Azure App Service:**
   ```bash
   az account show  # Verify you're logged in
   
   # Create resource group
   az group create --name trafficcams-rg --location eastus
   
   # Create App Service plan (Linux)
   az appservice plan create \
     --name trafficcams-plan \
     --resource-group trafficcams-rg \
     --sku B1 \
     --is-linux
   
   # Create web app
   az webapp create \
     --resource-group trafficcams-rg \
     --plan trafficcams-plan \
     --name trafficcams-app \
     --runtime "node|18-lts"
   ```

2. **Enable GitHub Deployment:**
   - Open Azure Portal
   - Go to your App Service
   - Click **Deployment Center**
   - Select **GitHub** as source
   - Authorize GitHub account
   - Select your repository and main branch
   - Click Save

3. **Access Your App:**
   ```
   https://trafficcams-app.azurewebsites.net
   ```

4. **Auto-Deploy on Every Push:**
   - Push changes to GitHub: `git push origin main`
   - Azure automatically deploys within 2-3 minutes
   - Check deployment status in Deployment Center

---

### Option 2: Heroku

```bash
# Install Heroku CLI
# https://devcenter.heroku.com/articles/heroku-cli

# Login to Heroku
heroku login

# Create app
heroku create trafficcams

# Set Node.js buildpack
heroku buildpacks:set heroku/nodejs

# Deploy
git push heroku main

# View app
heroku open

# View real-time logs
heroku logs --tail

# Redeploy (after git push)
git push heroku main
```

---

### Option 3: DigitalOcean App Platform

1. Connect GitHub account
2. Select your repository
3. DigitalOcean auto-detects Node.js
4. Sets PORT environment variable
5. Auto-deploys on every push

---

### Option 4: Vercel (Frontend-focused)

```bash
# Install Vercel CLI
npm i -g vercel

# Deploy
vercel

# Redeploy on push (with GitHub integration)
git push origin main
```

---

### Option 5: AWS EC2 + CodeDeploy

1. Create EC2 instance (Ubuntu 20.04)
2. Configure CodeDeploy agent
3. Create appspec.yml
4. Connect to GitHub via CodePipeline
5. Auto-deploy on push

---

## 📝 Environment Variables for Production

Add these to your hosting provider:

```
NODE_ENV=production
PORT=80
HOST=0.0.0.0
```

---

## 🔗 GitHub Features Setup

### Add Topics to Repository

In GitHub repository Settings:
- Add topics: `traffic` `camera` `dashboard` `wsdot` `nodejs` `express`

### Add GitHub Pages (Optional Portfolio)

In repository Settings:
- GitHub Pages → Source: main branch /docs folder
- Deploy live documentation

### Enable GitHub Actions (Auto-Test on Push)

Create `.github/workflows/ci.yml`:
```yaml
name: CI

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Use Node.js
        uses: actions/setup-node@v2
        with:
          node-version: '18'
      - run: npm install
      - run: npm test
      - run: npm start &
      - name: Health Check
        run: curl http://localhost:80/health
```

---

## 📊 GitHub Repository Best Practices

### Add .gitignore (Already Present ✅)

```
node_modules/
.env
.env.local
*.log
dist/
.DS_Store
```

### Add LICENSE

Create `LICENSE` file (MIT recommended):
```
MIT License

Copyright (c) 2026 [Your Name]

Permission is hereby granted...
```

### Create GitHub Releases

```bash
# Create a git tag
git tag -a v2.0 -m "Interactive Dashboard with 596 cameras"

# Push tag to GitHub
git push origin v2.0

# In GitHub: Create Release from tag with notes
```

### Enable Branch Protection

Settings → Branches → Add rule for `main`:
- ✅ Require pull request reviews
- ✅ Require status checks to pass
- ✅ Require branches to be up to date

---

## 🎯 Post-Deployment Checklist

- [ ] Repository pushed to GitHub
- [ ] Verify all files are present
- [ ] README.md displays correctly
- [ ] Hosting provider deployment configured
- [ ] Environment variables set
- [ ] CORS configured correctly
- [ ] Database/camera data accessible
- [ ] Dashboard loads at production URL
- [ ] Images load correctly
- [ ] Filters work as expected
- [ ] Modal opens on image click
- [ ] Mobile responsive on tablets
- [ ] Performance acceptable
- [ ] SSL/HTTPS enabled
- [ ] Custom domain configured (optional)
- [ ] GitHub Actions CI passing
- [ ] Deployment automation working

---

## 📞 Verify Production Deployment

```bash
# Check server health
curl https://YOUR-APP-URL/health

# Verify API endpoint
curl https://YOUR-APP-URL/api/cameras-enhanced | head -50

# Test dashboard loads
curl -I https://YOUR-APP-URL/dashboard.htm

# Monitor logs
# Azure: az webapp log tail --resource-group trafficcams-rg --name trafficcams-app
# Heroku: heroku logs --tail
```

---

## 🆘 Troubleshooting Production Deployment

### App won't start
- Check Node.js version matches (18+ required)
- Verify package.json is correct
- Check environment variables set
- Review deployment logs

### Port binding error
- Set PORT environment variable
- Use `process.env.PORT` in server.js
- Default should be configurable

### Images not loading
- Check CORS headers
- Verify WSDOT server is accessible from your hosting region
- Test with direct image URL

### Slow performance
- Enable gzip compression
- Use CDN for static assets
- Reduce initial camera data size
- Add caching headers

---

## 📚 Additional Resources

- [GitHub Documentation](https://docs.github.com)
- [Azure App Service](https://docs.microsoft.com/azure/app-service/)
- [Heroku Documentation](https://devcenter.heroku.com/)
- [Express.js Production Best Practices](https://expressjs.com/en/advanced/best-practice-performance.html)
- [Node.js Production Checklist](https://nodejs.org/en/docs/guides/nodejs-docker-webapp/)

---

## 🎉 Success!

Your Traffic Camera Dashboard is now deployed and accessible worldwide!

**Next Steps:**
- Share your GitHub repository link
- Submit to GitHub Trending
- Add to portfolio website
- Consider adding more features (see README roadmap)
- Collect feedback from users
