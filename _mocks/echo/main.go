package main

import (
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

var (
	port = flag.String("port", "8000", "port to listen on")
)

type Request struct {
	Datetime string
	Method   string
	Path     string
	Query    map[string][]string
	Headers  map[string][]string
	Body     string
	Duration int64
}

func echo(w http.ResponseWriter, req *http.Request) {
	log.Print("[" + time.Now().UTC().String() + "] " + req.Method + " " + req.URL.String())

	started := time.Now()
	r := Request{
		Datetime: time.Now().UTC().String(),
		Method:   req.Method,
		Path:     strings.Split(req.URL.String(), "?")[0],
		Query:    req.URL.Query(),
		Headers:  req.Header,
		Body:     "",
		Duration: time.Duration(0).Nanoseconds(),
	}
	if req.Body != nil {
		bodyBytes, err := io.ReadAll(req.Body)
		if err != nil {
			log.Printf("Body reading error: %v", err)
			return
		}
		r.Body = string(bodyBytes)
		defer req.Body.Close()
	}

	_, ok := r.Query["delay"]
	if ok && len(r.Query["delay"]) > 0 {
		delay, _ := time.ParseDuration(r.Query["delay"][0] + "s")
		log.Printf("Delay: %v\n", delay)
		time.Sleep(delay)
	}

	r.Duration = time.Since(started).Nanoseconds()

	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "HEAD, OPTIONS, GET, PUT, POST, DELETE")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization")

	rb, _ := json.Marshal(r)
	w.Write(rb)
}

func main() {
	flag.Parse()
	log.Print("Running echo server on " + *port)
	http.HandleFunc("/", echo)
	err := http.ListenAndServe(":"+*port, nil)
	log.Print("%v", err)
}
