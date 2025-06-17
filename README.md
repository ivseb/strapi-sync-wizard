# 🔄 StrapiSync Wizard 🧙‍♂️

> *Content synchronization magic for your Strapi CMS environments!*

![StrapiSyncLogo png](StrapiSyncLogoImg.png)


# StrapiSync Server

StrapiSync is a synchronization tool for [Strapi CMS](https://strapi.io/) that allows you to manage and synchronize content between different Strapi instances. It provides a user-friendly interface for reviewing, selecting, and merging content changes across environments.

## Features

- **Instance Management**: Configure and manage multiple Strapi instances
- **Content Synchronization**: Synchronize content between Strapi instances
- **Merge Requests**: Create, review, and complete merge requests for content changes
- **Diff Viewer**: Compare content differences between instances
- **Media Management**: Synchronize media files and folders
- **Selective Sync**: Choose which content types and entries to synchronize

## Architecture

StrapiSync is built with:

- **Backend**: Kotlin with Ktor framework
- **Frontend**: React with TypeScript and PrimeReact UI components
- **Database**: PostgreSQL for storing configuration and sync state
- **Docker**: Containerization support for easy deployment

## Prerequisites

- JDK 17 or higher
- PostgreSQL database
- Node.js and npm (for frontend development)
- Strapi instances (v4.x) to synchronize

## Configuration

The application can be configured through environment variables or the `application.conf` file:

### Server Configuration
- `PORT`: HTTP port (default: 8080)
- `HOST`: Host address (default: 0.0.0.0)
- `DEVELOPMENT_MODE`: Enable development mode (default: true)

### Database Configuration
- `JDBC_DATABASE_URL`: JDBC URL for PostgreSQL (default: jdbc:postgresql://localhost:5432/strapisync)
- `JDBC_DATABASE_USERNAME`: Database username (default: postgres)
- `JDBC_DATABASE_PASSWORD`: Database password (default: postgres)
- `JDBC_MAXIMUM_POOL_SIZE`: Maximum pool size for database connections (default: 3)
- `DB_SALT`: Salt used for database encryption (required, no default)

### Application Configuration
- `DATA_FOLDER`: Folder for storing temporary data (default: data)
- `STRAPI_CLIENT_TIMEOUT`: Timeout for Strapi API requests in milliseconds (default: 30000)
- `STRAPI_CLIENT_MAX_RETRIES`: Maximum retries for Strapi API requests (default: 3)
- `HTTP_PROXY`: HTTP proxy configuration (default: none)
- `HTTPS_PROXY`: HTTPS proxy configuration (default: none)
- `NO_PROXY`: No proxy configuration (default: none)

## Building and Running

### Building the Application

```bash
./gradlew build
```

### Running the Application

```bash
./gradlew run
```

### Building with Docker

```bash
./gradlew jibDockerBuild
```

### Docker Image

A public Docker image is available and can be used directly. You can find it at the following link:

[StrapiSyncWizard Docker Image](https://hub.docker.com/r/ivseb/strapi-sync-wizard)

To use the Docker image:

```bash
docker pull ivseb/strapi-sync-wizard:latest
docker run -p 8080:8080 ivseb/strapi-sync-wizard:latest
```

### Helm Chart

StrapiSyncWizard can be deployed on Kubernetes using the provided Helm chart. The chart is hosted on GitHub Pages at [https://ivseb.github.io/StrapiSyncWizard](https://ivseb.github.io/StrapiSyncWizard).

To use the Helm chart:

```bash
# Add the Helm repository
helm repo add strapi-sync-wizard https://ivseb.github.io/StrapiSyncWizard

# Update the repository
helm repo update

# Install the chart
helm install my-release strapi-sync-wizard/strapi-sync-wizard
```

For more information about the Helm chart and its configuration options, see the [Helm Chart README](./charts/strapi-sync-wizard/README.md).

## Usage

1. **Configure Strapi Instances**:
   - Add your Strapi instances with their URLs and authentication details

2. **Create Merge Requests**:
   - Select source and target instances
   - Review content differences
   - Select content to synchronize

3. **Complete Merges**:
   - Review the selected changes
   - Complete the merge to synchronize content

## Development

### Git Hooks Setup

This repository uses Git hooks to automate certain tasks. Specifically, a pre-commit hook is configured to:

1. Package the Helm chart: `helm package charts/strapi-sync-wizard -d charts/`
2. Update the Helm repository index: `helm repo index charts/ --url https://ivseb.github.io/strapi-sync-wizard/charts`
3. Automatically add any resulting changes to the Git staging area

The hooks are stored in the `.githooks` directory and are configured using:

```bash
git config core.hooksPath .githooks
```

New contributors should ensure this configuration is set up in their local repository.

### Backend Development

The backend is built with Kotlin and Ktor:

```bash
./gradlew run
```

### Frontend Development

The frontend is built with React:

```bash
cd frontend
npm install
npm start
```

## Technical Details

### Merge Request Workflow

The merge request workflow in StrapiSync involves several technical steps that interact with Strapi APIs:

1. **Creation**: When a merge request is created, the system stores information about source and target Strapi instances in the database.

2. **Schema Compatibility Check**: The system queries both Strapi instances via their APIs to retrieve content types and component schemas, then compares them to ensure compatibility.

3. **Content Comparison**: StrapiSync fetches content from both instances using Strapi's REST API, comparing entries to identify differences. This involves:
   - Authenticating with both Strapi instances using JWT tokens
   - Retrieving content entries with their relationships
   - Comparing content and identifying changes

4. **Selection Process**: Users select which content to synchronize, and the system tracks these selections in the database.

5. **Merge Execution**: When completing a merge request, the system:
   - Processes files first, downloading from source and uploading to target
   - Creates folder structures in the target if needed
   - Processes content entries in dependency order
   - Maintains relationships between content types
   - Updates document mappings to track synchronized content

6. **Status Updates**: Throughout the process, the merge request status is updated in the database to reflect progress.

All API interactions use the Strapi REST API with proper authentication, handling pagination, and managing relationships between content types.

## Project Background

This project was born out of a personal itch that needed scratching! I built StrapiSync Wizard in a relatively short time to solve a specific problem I was facing with content synchronization between Strapi environments. 

Is the code architecture perfect? Absolutely not! Did I follow all the best practices? Probably missed a few (or many)! But hey, it works, and that's what matters. The code might make seasoned developers cringe a bit, but it gets the job done.

If you're brave enough to continue this project or want to improve it, you're more than welcome to jump in. Just remember to bring your sense of humor and a lot of patience. The codebase might be a bit... let's say "creatively organized," but it's a fun challenge for anyone who enjoys untangling spaghetti code!

## License

This project is licensed under the MIT License - see the LICENSE file for details.
