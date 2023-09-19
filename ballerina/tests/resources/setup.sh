#!/bin/bash
# ---------------------------------------------------------------------------
#  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# Pull the Redis 5.0.7 Docker image
docker pull redis:5.0.7

# Create a Redis container and run it
docker run --name test-redis-container -d -p 6379:6379 redis:5.0.7

# Wait for Redis to start (optional, add a sleep or polling logic if needed)
sleep 5
