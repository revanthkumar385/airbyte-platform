import React from "react";
import { FormattedMessage } from "react-intl";

import { Box } from "components/ui/Box";
import { Card } from "components/ui/Card";
import { FlexContainer } from "components/ui/Flex";
import { Heading } from "components/ui/Heading";

import { useCurrentOrganizationInfo, useCurrentWorkspace } from "core/api";
import { useTrackPage, PageTrackingCodes } from "core/services/analytics";
import { useIntent } from "core/utils/rbac";
import { useExperiment } from "hooks/services/Experiment";
import WorkspaceAccessManagementSection from "pages/SettingsPage/pages/AccessManagementPage/WorkspaceAccessManagementSection";

import { DeleteCloudWorkspace, UpdateCloudWorkspaceName } from "./components";

export const WorkspaceSettingsView: React.FC = () => {
  useTrackPage(PageTrackingCodes.SETTINGS_WORKSPACE);
  const updatedOrganizationsUI = useExperiment("settings.organizationsUpdates", false);
  const organizationInfo = useCurrentOrganizationInfo();
  const hasSSORealm = organizationInfo?.sso;
  const { workspaceId } = useCurrentWorkspace();
  const canDeleteWorkspace = useIntent("DeleteWorkspace", { workspaceId });

  return (
    <FlexContainer direction="column" gap="xl">
      <Box p="xl">
        <Heading as="h2" size="md">
          <FormattedMessage id="settings.generalSettings" />
        </Heading>
      </Box>
      <Card>
        <Box p="xl">
          <UpdateCloudWorkspaceName />
        </Box>
      </Card>
      {hasSSORealm && updatedOrganizationsUI && (
        <Card>
          <Box p="xl">
            <FlexContainer direction="column" gap="xl">
              <WorkspaceAccessManagementSection />
            </FlexContainer>
          </Box>
        </Card>
      )}
      {canDeleteWorkspace && (
        <Card>
          <Box p="xl">
            <FlexContainer direction="column">
              <Heading as="h3" size="sm">
                <FormattedMessage id="settings.general.danger" />
              </Heading>
              <FlexContainer>
                <DeleteCloudWorkspace />
              </FlexContainer>
            </FlexContainer>
          </Box>
        </Card>
      )}
    </FlexContainer>

    /* {hasSSORealm && updatedOrganizationsUI &&  ( <Card>
          <Box p="xl">
            <FlexContainer direction="column" gap="xl">
           <WorkspaceAccessManagementSection />
             </FlexContainer>
</Box></Card>)}

<Card><Box>
               <Heading as="h3" size="sm">
                <FormattedMessage id="settings.general.danger" />
              </Heading><FlexContainer>{canDeleteWorkspace && <DeleteCloudWorkspace />}</FlexContainer>
            </FlexContainer>
          </Box>
        </Card>
      )}
    </FlexContainer>
  ); */
  );
};
