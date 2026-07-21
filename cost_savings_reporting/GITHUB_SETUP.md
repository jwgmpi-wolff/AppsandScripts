# GitHub Setup Guide for FinOps Dashboard

This guide will help you push the FinOps dashboard project to GitHub.

## Option 1: Create a New Repository (Recommended)

### Step 1: Create Repository on GitHub

1. Go to https://github.com/new
2. **Repository name**: `finops-dashboard`
3. **Description**: `Professional Azure FinOps Savings Dashboard with OAuth2 and Advisor integration`
4. **Visibility**: Public (or Private if you prefer)
5. **Initialize repository**: Leave unchecked (we'll push existing code)
6. Click **Create repository**

### Step 2: Initialize Local Git Repository

```powershell
cd c:\.git\cost_savings_reporting

# Initialize git if not already done
git init

# Configure git user (if not already set globally)
git config user.name "Your Name"
git config user.email "your.email@microsoft.com"

# Add all files
git add .

# Create initial commit
git commit -m "Initial commit: FinOps Dashboard with OAuth2, Advisor integration, and autostart"

# Add remote repository
git remote add origin https://github.com/YOUR_USERNAME/finops-dashboard.git

# Rename branch to main
git branch -M main

# Push to GitHub
git push -u origin main
```

### Step 3: Verify on GitHub

- Go to your repository: `https://github.com/YOUR_USERNAME/finops-dashboard`
- Verify all files are present (dashboard_api.py, dashboard.html, cost_api.py, README.md, etc.)
- Check that the comprehensive recreate prompt is visible in the README

---

## Option 2: Push to Existing Repository

If you already have a GitHub repository where you want to add this project:

```powershell
cd c:\.git\cost_savings_reporting

# Add your existing repository as remote
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git

# Add files
git add .

# Commit
git commit -m "Add FinOps Dashboard: Real-time Azure cost analysis with Advisor integration"

# Push
git push origin main  # or your branch name
```

---

## Option 3: Push Current Folder as Subtree (if adding to existing repo)

If you want to add this as part of a larger project:

```powershell
# From your main repo
git subtree add --prefix finops-dashboard c:\.git\cost_savings_reporting main
```

---

## After Pushing: GitHub Actions Setup (Optional)

### CI/CD Pipeline

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        python-version: ['3.10', '3.11']

    steps:
    - uses: actions/checkout@v3
    
    - name: Set up Python
      uses: actions/setup-python@v4
      with:
        python-version: ${{ matrix.python-version }}
    
    - name: Install dependencies
      run: |
        python -m pip install --upgrade pip
        pip install -r requirements.txt
        pip install pytest pytest-asyncio black pylint
    
    - name: Lint with pylint
      run: pylint dashboard_api.py cost_api.py
    
    - name: Format check with black
      run: black --check dashboard_api.py cost_api.py
    
    - name: Run tests
      run: pytest tests/
```

### Docker Build & Push (Optional)

Create `.github/workflows/docker.yml`:

```yaml
name: Docker Build

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: docker/setup-buildx-action@v2
      - uses: docker/login-action@v2
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}
      - uses: docker/build-push-action@v4
        with:
          context: .
          push: true
          tags: ${{ secrets.DOCKER_USERNAME }}/finops-dashboard:${{ github.ref_name }}
```

---

## Repository Structure

```
finops-dashboard/
├── README.md                      # Full documentation with recreate prompt
├── GITHUB_SETUP.md               # This file
├── requirements.txt              # Python dependencies
├── dashboard_api.py              # FastAPI server (460+ lines)
├── cost_api.py                   # Azure Cost Management client
├── dashboard.html                # Frontend UI (800+ lines)
├── examples.py                   # Usage examples
├── Dockerfile                    # Container image
├── docker-compose.yml            # Multi-container setup
├── setup.sh / setup.bat          # Installation scripts
├── .env.example                  # Environment template
├── .gitignore                    # Git ignore rules
├── .vscode/
│   ├── tasks.json               # Auto-start configuration
│   ├── launch.json              # Debug configuration
│   ├── settings.json            # Workspace settings
│   └── extensions.json          # Recommended extensions
├── tests/
│   ├── test_cost_api.py
│   └── test_dashboard_api.py
└── GETTING_STARTED.md           # Quick start guide
```

---

## Tags & Versioning

Create releases for each version:

```powershell
# After pushing commits

# Create a version tag
git tag -a v1.0.0 -m "Release v1.0.0: Initial stable release"

# Push tags
git push origin --tags
```

GitHub will automatically create a release from the tag.

---

## Adding to README on GitHub

The comprehensive recreate prompt is already in your README.md in the **"Recreate This Project"** section. This will be visible on GitHub and can be used by anyone to rebuild the project using AI assistants or Copilot.

---

## Common Git Commands

```powershell
# Check status
git status

# View commit history
git log --oneline -10

# View changes
git diff

# Undo last commit (keep changes)
git reset --soft HEAD~1

# Undo last commit (discard changes)
git reset --hard HEAD~1

# Update from remote
git pull origin main

# Create new branch
git checkout -b feature/new-feature

# Merge branch
git checkout main
git merge feature/new-feature

# Push branch
git push origin feature/new-feature
```

---

## Troubleshooting

### "Failed to connect to GitHub"
- Check internet connection
- Verify SSH key is configured: `ssh -T git@github.com`
- Or use HTTPS and verify credentials

### "Permission denied"
- For SSH: Generate SSH key and add to GitHub
  ```powershell
  ssh-keygen -t ed25519 -C "your.email@microsoft.com"
  # Add ~/.ssh/id_ed25519.pub to GitHub Settings > SSH Keys
  ```
- For HTTPS: Use personal access token instead of password
  ```powershell
  git config credential.helper store
  # Next push will prompt for token (create at https://github.com/settings/tokens)
  ```

### ".git already exists"
- If you get "fatal: not a git repository", run: `git init`
- If you get duplicate remotes, remove old one: `git remote remove origin`

---

## Next Steps

1. ✅ Pushed to GitHub
2. 📝 Add GitHub Pages documentation (optional)
3. 🐳 Publish Docker image to Docker Hub (optional)
4. 📊 Enable GitHub Actions for CI/CD (optional)
5. 🏷️ Create releases with version tags (optional)

---

For questions or issues, check the README.md "Troubleshooting" section or GitHub Issues.
