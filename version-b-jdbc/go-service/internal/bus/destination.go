// Package bus joins Spring Cloud Bus, so go-service is refreshed by the SAME broadcast that
// refreshes the Spring Boot services - no polling.
package bus

import (
	"regexp"
	"strings"
)

// IsForThisInstance reports whether a bus event is addressed to this instance, using the same
// rule as Spring's ServiceMatcher: destinationService is an Ant-style pattern matched against
// the bus id "<application>:<port>:<unique id>", with ":" as the separator.
//
//	"**"                -> every instance of every application (POST /actuator/busrefresh)
//	"go-service:**"     -> every instance of go-service
//	"go-service:8085:*" -> instances listening on port 8085
func IsForThisInstance(destination, busID string) bool {
	destination = strings.TrimSpace(destination)
	if destination == "" || destination == "**" {
		return true
	}
	return patternToRegexp(destination).MatchString(busID) ||
		patternToRegexp(destination+":**").MatchString(busID)
}

func patternToRegexp(pattern string) *regexp.Regexp {
	segments := strings.Split(pattern, ":")
	for i, segment := range segments {
		if segment == "**" {
			segments[i] = ".*"
			continue
		}
		segments[i] = strings.ReplaceAll(regexp.QuoteMeta(segment), `\*`, `[^:]*`)
	}
	source := strings.Join(segments, ":")
	source = strings.TrimSuffix(source, ":.*")
	if source != strings.Join(segments, ":") {
		source += "(:.*)?" // "app:**" also matches a bare "app"
	}
	return regexp.MustCompile("^" + source + "$")
}
