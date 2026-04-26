package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"time"

	"satiksmebot/internal/catalog"
	"satiksmebot/internal/config"
)

func main() {
	cfg, err := config.LoadCatalogOnly()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	mirrorDir := flag.String("mirror-dir", cfg.CatalogMirrorDir, "directory for mirrored source files")
	outputPath := flag.String("out", cfg.CatalogOutputPath, "path to generated compact catalog json")
	force := flag.Bool("force", false, "force refresh even when mirrored files are fresh")
	flag.Parse()

	manager := catalog.NewManager(catalog.Settings{
		StopsURL:     cfg.SourceStopsURL,
		RoutesURL:    cfg.SourceRoutesURL,
		GTFSURL:      cfg.SourceGTFSURL,
		MirrorDir:    *mirrorDir,
		OutputPath:   *outputPath,
		RefreshAfter: time.Duration(cfg.CatalogRefreshHours) * time.Hour,
		HTTPClient:   &http.Client{Timeout: time.Duration(cfg.HTTPTimeoutSec) * time.Second},
	})
	result, err := manager.Refresh(context.Background(), *force)
	if err != nil {
		log.Fatalf("catalog refresh: %v", err)
	}
	fmt.Printf("%s (%d stops, %d routes)\n", *outputPath, len(result.Stops), len(result.Routes))
}
