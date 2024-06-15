# Clod 
Simple Clojure backend I wrote to re-familiarize myself with Clojure.
It has a minimal Redis-inspired KV-store implmentation for session management and caching, 
SQLite for storing Users and their 'tasks', Hiccup for templating, and a Ring+Compojure REST API 
to glue it all together

# How to run
- Download Clojure
- Clone repo
- In root: `clj -X core/run`
- Open `localhost:8080` and log in :)
