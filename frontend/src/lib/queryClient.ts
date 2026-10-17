import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";

/**
 * Query defaults chosen for an operator console driven by a local backend.
 *
 * - Retrying a 4xx is pointless (the request is wrong, not unlucky), so only 5xx and
 *   network failures are retried, and only twice.
 * - Data is refetched on window focus but not on every mount, which keeps navigation
 *   snappy without showing stale job progress for long.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: true,
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
    },
    mutations: {
      retry: false,
    },
  },
});
