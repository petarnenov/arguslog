import { Center, Loader } from '@mantine/core';
import { Navigate } from 'react-router';

import { useMyOrgs } from '../api/queries';

/**
 * Lands the user on /onboarding if they have no orgs yet, or on the first org's projects list
 * otherwise. Replaces the old hard-coded redirect from /orgs → /onboarding which left users with
 * existing orgs stuck if they navigated to /orgs directly.
 */
export function OrgsLandingPage() {
  const orgs = useMyOrgs();

  if (orgs.isLoading) {
    return (
      <Center mih="40vh">
        <Loader size="md" />
      </Center>
    );
  }
  const first = orgs.data?.[0];
  if (!first) {
    return <Navigate to="/onboarding" replace />;
  }
  return <Navigate to={`/orgs/${first.slug}/projects`} replace />;
}
