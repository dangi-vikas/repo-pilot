import { Link } from "react-router-dom";
import { Card, EmptyState, PageHeader } from "@/components/ui";

export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Not found" description="That route does not exist in the console." />
      <Card>
        <EmptyState
          icon="alert"
          title="Nothing at this address"
          message="The address you followed is not one of RepoPilot's destinations."
          action={
            <Link to="/overview" className="btn btn-primary btn-sm">
              Back to Overview
            </Link>
          }
        />
      </Card>
    </>
  );
}
