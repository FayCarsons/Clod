# Clod 
Simple Clojure backend I wrote to re-familiarize myself with Clojure.
It has a minimal Redis-inspired KV-store implementation for session management and caching, 
SQLite for storing Users and their 'tasks', Hiccup for templating, and a Ring+Compojure REST API 
to glue it all together.

I don't know why I named it Clod.

On opening, you will be greeted with a login page, log in and start adding, editing, and removing 'tasks'. 
A session cookie will be created so you (shouldn't) have to login again for 10 minutes.

*Currently not quite finished, expect things to be broken*

# How to run
- Download Clojure
- Clone repo
- In root: `clj -X core/run`
- Open `localhost:8080` and log in :)
